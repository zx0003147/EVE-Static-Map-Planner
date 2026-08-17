package dev.evestaticmapplanner.data.ansiblex

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection

enum class AnsiblexImportMode {
    MERGE,
    REPLACE,
}

enum class ImportDiagnosticSeverity {
    WARNING,
    ERROR,
}

data class ImportDiagnostic(
    val severity: ImportDiagnosticSeverity,
    val code: String,
    val message: String,
    val rowNumber: Long? = null,
    val field: String? = null,
)

data class ImportCandidate(
    val firstSystemId: Int,
    val secondSystemId: Int,
    val direction: AnsiblexDirection,
    val displayName: String?,
    val notes: String?,
    val enabled: Boolean,
    val rowNumber: Long,
)

data class ImportChange(
    val candidate: ImportCandidate,
    val existing: AnsiblexConnection? = null,
)

data class AnsiblexImportPreview(
    val sourceFileName: String,
    val sourceFileSha256: String,
    val mode: AnsiblexImportMode,
    val rawRowCount: Int,
    val validRowCount: Int,
    val invalidRowCount: Int,
    val duplicateCount: Int,
    val additions: List<ImportChange>,
    val updates: List<ImportChange>,
    val unchanged: List<ImportChange>,
    val removals: List<AnsiblexConnection>,
    val diagnostics: List<ImportDiagnostic>,
    internal val candidates: List<ImportCandidate>,
    internal val baseSnapshotFingerprint: String,
) {
    val canApply: Boolean get() = diagnostics.none { it.severity == ImportDiagnosticSeverity.ERROR }
}

data class AnsiblexImportApplyResult(
    val batchId: String,
    val addedCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int,
    val removedCount: Int,
)

class StaleImportPreviewException : IllegalStateException(
    "Ansiblex data changed after preview; create a new preview before applying",
)

class InvalidImportPreviewException : IllegalArgumentException(
    "Ansiblex import preview contains errors and cannot be applied",
)

internal data class RawImportEndpoint(
    val systemId: Int?,
    val systemName: String?,
)

internal data class RawImportRow(
    val rowNumber: Long,
    val from: RawImportEndpoint,
    val to: RawImportEndpoint,
    val displayName: String?,
    val notes: String?,
    val enabled: Boolean?,
    val direction: String?,
)

internal data class ParsedImport(
    val rows: List<RawImportRow>,
    val diagnostics: List<ImportDiagnostic>,
)
