package dev.evestaticmapplanner.data.ansiblex

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.repository.UniverseRepository
import dev.evestaticmapplanner.data.db.UserDatabase
import dev.evestaticmapplanner.data.repository.insertAnsiblex
import dev.evestaticmapplanner.data.repository.selectAllAnsiblex
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

class AnsiblexImportService(
    private val userDatabasePath: Path,
    private val universeRepository: UniverseRepository,
    private val searchRepository: SystemSearchRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val transactionHook: (Connection) -> Unit = {},
    initializeDatabase: Boolean = true,
) {
    init {
        if (initializeDatabase) UserDatabase.initialize(userDatabasePath)
    }

    fun preview(file: Path, mode: AnsiblexImportMode): AnsiblexImportPreview {
        val bytes = Files.readAllBytes(file)
        val parsed = AnsiblexImportParsers.parse(file.fileName.toString(), bytes.toString(StandardCharsets.UTF_8))
        return createPreview(
            sourceFileName = file.fileName.toString(),
            sourceFileSha256 = sha256(bytes),
            mode = mode,
            parsed = parsed,
        )
    }

    fun previewText(
        sourceFileName: String,
        text: String,
        mode: AnsiblexImportMode,
    ): AnsiblexImportPreview = createPreview(
        sourceFileName = sourceFileName,
        sourceFileSha256 = sha256(text.toByteArray(StandardCharsets.UTF_8)),
        mode = mode,
        parsed = AnsiblexImportParsers.parse(sourceFileName, text),
    )

    fun apply(preview: AnsiblexImportPreview): AnsiblexImportApplyResult {
        if (!preview.canApply) throw InvalidImportPreviewException()
        val batchId = idGenerator()
        val now = clock.instant()
        UserDatabase.open(userDatabasePath).use { connection ->
            connection.autoCommit = false
            try {
                val current = connection.selectAllAnsiblex()
                if (snapshotFingerprint(current) != preview.baseSnapshotFingerprint) {
                    throw StaleImportPreviewException()
                }
                insertBatch(connection, batchId, now, preview)
                preview.removals.forEach { existing ->
                    connection.prepareStatement("DELETE FROM ansiblex_connections WHERE id = ? AND source = 'IMPORT'").use {
                        it.setString(1, existing.id)
                        check(it.executeUpdate() == 1) { "Expected imported connection ${existing.id} to be removed" }
                    }
                }
                preview.updates.forEach { change ->
                    val existing = checkNotNull(change.existing)
                    connection.prepareStatement(
                        """
                        UPDATE ansiblex_connections
                        SET direction = ?, display_name = ?, notes = ?, source_batch_id = ?, enabled = ?, updated_at = ?
                        WHERE id = ? AND source = 'IMPORT'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, change.candidate.direction.name)
                        statement.setString(2, change.candidate.displayName)
                        statement.setString(3, change.candidate.notes)
                        statement.setString(4, batchId)
                        statement.setInt(5, if (change.candidate.enabled) 1 else 0)
                        statement.setString(6, now.toString())
                        statement.setString(7, existing.id)
                        check(statement.executeUpdate() == 1) { "Expected imported connection ${existing.id} to be updated" }
                    }
                }
                preview.additions.forEach { change ->
                    connection.insertAnsiblex(change.candidate.toConnection(idGenerator(), batchId, now))
                }
                transactionHook(connection)
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
        return AnsiblexImportApplyResult(
            batchId = batchId,
            addedCount = preview.additions.size,
            updatedCount = preview.updates.size,
            unchangedCount = preview.unchanged.size,
            removedCount = preview.removals.size,
        )
    }

    private fun createPreview(
        sourceFileName: String,
        sourceFileSha256: String,
        mode: AnsiblexImportMode,
        parsed: ParsedImport,
    ): AnsiblexImportPreview {
        val diagnostics = parsed.diagnostics.toMutableList()
        if (parsed.rows.isEmpty()) {
            diagnostics += error("EMPTY_IMPORT", "Ansiblex import contains no connection rows")
        }
        val invalidRows = mutableSetOf<Long>()
        diagnostics.mapNotNullTo(invalidRows, ImportDiagnostic::rowNumber)
        val resolved = mutableListOf<ImportCandidate>()
        val systemById = mutableMapOf<Int, Int?>()
        val systemByName = mutableMapOf<String, Int?>()

        fun resolve(endpoint: RawImportEndpoint, row: RawImportRow, field: String): Int? {
            if (endpoint.systemId == null && endpoint.systemName == null) {
                diagnostics += error("MISSING_ENDPOINT", "$field requires a system ID or exact system name", row.rowNumber, field)
                invalidRows += row.rowNumber
                return null
            }
            val byId = endpoint.systemId?.let { id ->
                systemById.getOrPut(id) { universeRepository.getSystem(id)?.id }
            }
            if (endpoint.systemId != null && byId == null) {
                diagnostics += error("UNKNOWN_SYSTEM_ID", "Unknown solar system ID: ${endpoint.systemId}", row.rowNumber, field)
                invalidRows += row.rowNumber
            }
            val byName = endpoint.systemName?.let { name ->
                systemByName.getOrPut(name.lowercase()) {
                    searchRepository.searchSystems(name, 20)
                        .filter { it.name.equals(name, ignoreCase = true) }
                        .singleOrNull()
                        ?.id
                }
            }
            if (endpoint.systemName != null && byName == null) {
                diagnostics += error("UNKNOWN_SYSTEM_NAME", "Unknown or ambiguous solar system name: ${endpoint.systemName}", row.rowNumber, field)
                invalidRows += row.rowNumber
            }
            if (byId != null && byName != null && byId != byName) {
                diagnostics += error("ENDPOINT_MISMATCH", "$field system ID and name refer to different systems", row.rowNumber, field)
                invalidRows += row.rowNumber
                return null
            }
            return byId ?: byName
        }

        parsed.rows.forEach { row ->
            val from = resolve(row.from, row, "from")
            val to = resolve(row.to, row, "to")
            val direction = when (row.direction?.uppercase() ?: "BIDIRECTIONAL") {
                "BIDIRECTIONAL" -> AnsiblexDirection.BIDIRECTIONAL
                "FORWARD" -> if (from != null && to != null && from > to) {
                    AnsiblexDirection.SECOND_TO_FIRST
                } else {
                    AnsiblexDirection.FIRST_TO_SECOND
                }
                else -> {
                    diagnostics += error(
                        "INVALID_DIRECTION",
                        "direction must be BIDIRECTIONAL or FORWARD: ${row.direction}",
                        row.rowNumber,
                        "direction",
                    )
                    invalidRows += row.rowNumber
                    null
                }
            }
            if (from != null && to != null && from == to) {
                diagnostics += error("SELF_LOOP", "Ansiblex connection cannot link a system to itself", row.rowNumber)
                invalidRows += row.rowNumber
            }
            if (row.rowNumber !in invalidRows && from != null && to != null && direction != null) {
                resolved += ImportCandidate(
                    firstSystemId = minOf(from, to),
                    secondSystemId = maxOf(from, to),
                    direction = direction,
                    displayName = row.displayName?.trim()?.takeIf(String::isNotEmpty),
                    notes = row.notes?.trim()?.takeIf(String::isNotEmpty),
                    enabled = row.enabled ?: true,
                    rowNumber = row.rowNumber,
                )
            }
        }

        val unique = mutableListOf<ImportCandidate>()
        var duplicateCount = 0
        resolved.groupBy { it.firstSystemId to it.secondSystemId }.values.forEach { group ->
            val first = group.first()
            val conflicts = group.drop(1).filter { !it.sameContent(first) }
            if (conflicts.isNotEmpty()) {
                group.forEach { invalidRows += it.rowNumber }
                diagnostics += error(
                    "CONFLICTING_DUPLICATE",
                    "Connection ${first.firstSystemId}-${first.secondSystemId} is repeated with conflicting values",
                    first.rowNumber,
                )
            } else {
                unique += first
                duplicateCount += group.size - 1
                group.drop(1).forEach {
                    diagnostics += ImportDiagnostic(
                        ImportDiagnosticSeverity.WARNING,
                        "DUPLICATE",
                        "Identical duplicate of connection ${first.firstSystemId}-${first.secondSystemId} was ignored",
                        it.rowNumber,
                    )
                }
            }
        }

        val existing = UserDatabase.open(userDatabasePath).use(Connection::selectAllAnsiblex)
        val existingByPair = existing.associateBy { it.firstSystemId to it.secondSystemId }
        val additions = mutableListOf<ImportChange>()
        val updates = mutableListOf<ImportChange>()
        val unchanged = mutableListOf<ImportChange>()
        unique.forEach { candidate ->
            val current = existingByPair[candidate.firstSystemId to candidate.secondSystemId]
            when {
                current == null -> additions += ImportChange(candidate)
                current.source == AnsiblexSource.MANUAL -> {
                    invalidRows += candidate.rowNumber
                    diagnostics += error(
                        "MANUAL_SOURCE_CONFLICT",
                        "Import cannot overwrite manual connection ${candidate.firstSystemId}-${candidate.secondSystemId}",
                        candidate.rowNumber,
                    )
                }
                candidate.matches(current) -> unchanged += ImportChange(candidate, current)
                else -> updates += ImportChange(candidate, current)
            }
        }
        val candidatePairs = unique.mapTo(hashSetOf()) { it.firstSystemId to it.secondSystemId }
        val removals = if (mode == AnsiblexImportMode.REPLACE) {
            existing.filter { it.source == AnsiblexSource.IMPORT && (it.firstSystemId to it.secondSystemId) !in candidatePairs }
        } else {
            emptyList()
        }
        return AnsiblexImportPreview(
            sourceFileName = sourceFileName,
            sourceFileSha256 = sourceFileSha256,
            mode = mode,
            rawRowCount = parsed.rows.size,
            validRowCount = unique.count { it.rowNumber !in invalidRows },
            invalidRowCount = invalidRows.size + diagnostics.count { it.rowNumber == null && it.severity == ImportDiagnosticSeverity.ERROR },
            duplicateCount = duplicateCount,
            additions = additions.filter { it.candidate.rowNumber !in invalidRows },
            updates = updates.filter { it.candidate.rowNumber !in invalidRows },
            unchanged = unchanged.filter { it.candidate.rowNumber !in invalidRows },
            removals = removals,
            diagnostics = diagnostics.sortedWith(compareBy({ it.rowNumber ?: 0L }, { it.severity.ordinal }, ImportDiagnostic::code)),
            candidates = unique.filter { it.rowNumber !in invalidRows },
            baseSnapshotFingerprint = snapshotFingerprint(existing),
        )
    }

    private fun insertBatch(
        connection: Connection,
        batchId: String,
        importedAt: Instant,
        preview: AnsiblexImportPreview,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO ansiblex_import_batches(
                batch_id, source_file_name, source_file_sha256, imported_at, mode,
                added_count, updated_count, unchanged_count, removed_count, error_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, batchId)
            statement.setString(2, preview.sourceFileName)
            statement.setString(3, preview.sourceFileSha256)
            statement.setString(4, importedAt.toString())
            statement.setString(5, preview.mode.name)
            statement.setInt(6, preview.additions.size)
            statement.setInt(7, preview.updates.size)
            statement.setInt(8, preview.unchanged.size)
            statement.setInt(9, preview.removals.size)
            statement.executeUpdate()
        }
    }

    private fun ImportCandidate.toConnection(id: String, batchId: String, now: Instant) = AnsiblexConnection(
        id = id,
        firstSystemId = firstSystemId,
        secondSystemId = secondSystemId,
        direction = direction,
        displayName = displayName,
        notes = notes,
        source = AnsiblexSource.IMPORT,
        sourceBatchId = batchId,
        enabled = enabled,
        createdAt = now,
        updatedAt = now,
    )

    private fun ImportCandidate.matches(connection: AnsiblexConnection): Boolean =
        direction == connection.direction &&
            displayName == connection.displayName &&
            notes == connection.notes &&
            enabled == connection.enabled

    private fun ImportCandidate.sameContent(other: ImportCandidate): Boolean =
        firstSystemId == other.firstSystemId &&
            secondSystemId == other.secondSystemId &&
            direction == other.direction &&
            displayName == other.displayName &&
            notes == other.notes &&
            enabled == other.enabled
}

internal fun snapshotFingerprint(connections: List<AnsiblexConnection>): String = sha256(
    connections.sortedWith(compareBy({ it.firstSystemId }, { it.secondSystemId }, { it.id }))
        .joinToString("\n") {
            listOf(
                it.id,
                it.firstSystemId,
                it.secondSystemId,
                it.direction,
                it.displayName,
                it.notes,
                it.source,
                it.sourceBatchId,
                it.enabled,
                it.createdAt,
                it.updatedAt,
            ).joinToString("|")
        }.toByteArray(StandardCharsets.UTF_8),
)

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun error(code: String, message: String, rowNumber: Long? = null, field: String? = null) =
    ImportDiagnostic(ImportDiagnosticSeverity.ERROR, code, message, rowNumber, field)
