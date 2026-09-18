package dev.evestaticmapplanner.webpack

import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDraft
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.model.Constellation
import dev.evestaticmapplanner.core.model.Region
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.AnsiblexRepository
import dev.evestaticmapplanner.core.repository.StaticMapRepository
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebPackExporterTest {
    private val generatedAt = Instant.parse("2026-09-08T01:02:03Z")
    private val clock = Clock.fixed(generatedAt, ZoneOffset.UTC)

    @Test
    fun `normal export writes a matching manifest and round-trippable gzip pack`() {
        val output = createTempDirectory("web-pack-export")
        val repository = TrackingAnsiblexRepository(
            listOf(
                ansiblex("enabled", 1, 3, enabled = true, displayName = "Useful bridge"),
                ansiblex("disabled", 1, 2, enabled = false),
            ),
        )
        val report = exporter(fixture(), repository).export(request(output))

        assertEquals(WebPackSchema.VERSION, report.schemaVersion)
        assertEquals(WebPackCounts(3, 2, 1, 1, 1), report.counts)
        assertTrue(Files.isRegularFile(report.manifestPath))
        assertTrue(Files.isRegularFile(report.packPath))
        assertEquals(report.packSizeBytes, Files.size(report.packPath))
        assertTrue(report.packSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(Files.list(output).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })

        val manifest = WebPackCodec.decodeManifest(Files.readAllBytes(report.manifestPath))
        val document = WebPackCodec.decodePack(Files.readAllBytes(report.packPath))
        assertEquals(report.packVersion, manifest.packVersion)
        assertEquals(report.packVersion, document.packVersion)
        assertEquals(report.generatedAt, manifest.generatedAt)
        assertEquals(report.generatedAt, document.generatedAt)
        assertEquals(manifest.fileName, report.packPath.fileName.toString())
        assertEquals(manifest.sizeBytes, report.packSizeBytes)
        assertEquals(manifest.sha256, report.packSha256)
        assertEquals(manifest.counts, document.counts)
        assertEquals(3_466_501L, document.sdeBuild)
        assertEquals("1.7.0", document.desktopAppVersion)
        assertEquals(listOf("enabled"), document.payload.ansiblexLinks.map(WebPackAnsiblexLink::id))
        assertTrue(document.payload.ansiblexLinks.single().enabled)
        assertEquals(WebPackAnsiblexDirection.BIDIRECTIONAL, document.payload.ansiblexLinks.single().direction)
        assertEquals(WebPackPosition2D(10.0, 20.0), document.payload.systems.first().officialPosition)
        assertEquals(25, document.payload.systems.last().effectiveWormholeClassId)
        assertFalse(repository.mutated)
    }

    @Test
    fun `pack codec serialization and deserialization round trip`() {
        val payload = fixturePayload()
        val document = WebPackDocument(
            schemaVersion = WebPackSchema.VERSION,
            packVersion = "fixture-pack",
            generatedAt = generatedAt.toString(),
            desktopAppVersion = "1.7.0",
            sdeBuild = 3_466_501L,
            counts = payload.counts(),
            payload = payload,
        )

        assertEquals(document, WebPackCodec.decodePack(WebPackCodec.encodePack(document)))
    }

    @Test
    fun `unsupported manifest schema and corrupt pack are rejected`() {
        val output = createTempDirectory("web-pack-rejection")
        val report = exporter(fixture()).export(request(output))
        val unsupportedManifest = Files.readString(report.manifestPath)
            .replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":999")

        val schemaError = assertFailsWith<WebPackValidationException> {
            WebPackCodec.decodeManifest(unsupportedManifest.encodeToByteArray())
        }
        assertTrue(schemaError.message.orEmpty().contains("unsupported"))

        val corrupt = Files.readAllBytes(report.packPath).copyOf().also { it[0] = 0 }
        assertFails { WebPackCodec.decodePack(corrupt) }
    }

    @Test
    fun `stargate endpoint integrity is enforced`() {
        val invalid = fixture().copy(
            connections = listOf(StargateConnection.between(1, 99)),
        )

        val error = assertFailsWith<WebPackExportException> {
            exporter(invalid).export(request(createTempDirectory("web-pack-bad-gate")))
        }
        assertTrue(error.message.orEmpty().contains("Stargate references missing solar system 99"))
    }

    @Test
    fun `enabled Ansiblex endpoint integrity is enforced`() {
        val invalid = TrackingAnsiblexRepository(listOf(ansiblex("invalid", 1, 99, enabled = true)))

        val error = assertFailsWith<WebPackExportException> {
            exporter(fixture(), invalid).export(request(createTempDirectory("web-pack-bad-ansiblex")))
        }
        assertTrue(error.message.orEmpty().contains("Ansiblex invalid references missing solar system 99"))
    }

    @Test
    fun `disabled Ansiblex is excluded before active-link integrity validation`() {
        val repository = TrackingAnsiblexRepository(
            listOf(
                ansiblex("active", 1, 3, enabled = true),
                ansiblex("disabled-invalid", 1, 99, enabled = false),
            ),
        )

        val report = exporter(fixture(), repository).export(request(createTempDirectory("web-pack-disabled")))
        val pack = WebPackCodec.decodePack(Files.readAllBytes(report.packPath))
        assertEquals(listOf("active"), pack.payload.ansiblexLinks.map(WebPackAnsiblexLink::id))
        assertEquals(1, pack.counts.ansiblexLinks)
    }

    @Test
    fun `region and constellation references are validated`() {
        val payload = fixturePayload().copy(
            constellations = listOf(WebPackConstellation(10, 99, "Missing region")),
        )

        val error = assertFailsWith<WebPackValidationException> { WebPackValidator.validate(payload) }
        assertTrue(error.message.orEmpty().contains("references missing region 99"))
    }

    private fun exporter(
        data: StaticMapData,
        ansiblexRepository: AnsiblexRepository = TrackingAnsiblexRepository(emptyList()),
    ) = WebPackExporter(StaticMapRepository { data }, ansiblexRepository, clock)

    private fun request(output: java.nio.file.Path) = WebPackExportRequest(
        outputDirectory = output,
        desktopAppVersion = "1.7.0",
        sdeBuild = 3_466_501L,
    )

    private fun fixture(): StaticMapData = StaticMapData(
        systems = listOf(
            system(1, "Alpha", 10.0, 20.0),
            system(2, "Beta", 30.0, 40.0),
            system(3, "Gamma", 50.0, 60.0, effectiveWormholeClassId = 25),
        ),
        connections = listOf(
            StargateConnection.between(1, 2),
            StargateConnection.between(2, 3),
        ),
        regions = listOf(Region(100, "Fixture Region", UniversePosition(0.0, 0.0, 0.0), null, "测试星域")),
        constellations = listOf(Constellation(10, 100, "Fixture Constellation", UniversePosition(0.0, 0.0, 0.0), null)),
    )

    private fun fixturePayload(): WebPackPayload = WebPackPayload(
        systems = listOf(
            WebPackSolarSystem(
                id = 1,
                name = "Alpha",
                regionId = 100,
                constellationId = 10,
                securityStatus = 0.1,
                universePosition = WebPackPosition3D(1.0, 2.0, 3.0),
                officialPosition = WebPackPosition2D(10.0, 20.0),
                effectiveWormholeClassId = null,
            ),
            WebPackSolarSystem(
                id = 2,
                name = "Beta",
                regionId = 100,
                constellationId = 10,
                securityStatus = -0.1,
                universePosition = WebPackPosition3D(4.0, 5.0, 6.0),
                officialPosition = null,
                effectiveWormholeClassId = 7,
            ),
        ),
        stargateLinks = listOf(WebPackStargateLink(1, 2)),
        regions = listOf(WebPackRegion(100, "Fixture Region")),
        constellations = listOf(WebPackConstellation(10, 100, "Fixture Constellation")),
        ansiblexLinks = emptyList(),
    )

    private fun system(
        id: Int,
        name: String,
        officialX: Double,
        officialY: Double,
        effectiveWormholeClassId: Int? = null,
    ) = SolarSystem(
        id = id,
        constellationId = 10,
        regionId = 100,
        name = name,
        securityStatus = if (id == 3) -0.5 else 0.25,
        securityClass = null,
        position = UniversePosition(id * 1.0e15, id * 2.0e15, id * 3.0e15),
        schematicPosition = SchematicPosition(officialX, officialY),
        radius = 1.0,
        factionId = null,
        wormholeClassId = effectiveWormholeClassId,
        effectiveWormholeClassId = effectiveWormholeClassId,
    )

    private fun ansiblex(
        id: String,
        first: Int,
        second: Int,
        enabled: Boolean,
        displayName: String? = null,
    ) = AnsiblexConnection(
        id = id,
        firstSystemId = first,
        secondSystemId = second,
        direction = AnsiblexDirection.BIDIRECTIONAL,
        displayName = displayName,
        notes = "must not be exported",
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = enabled,
        createdAt = generatedAt,
        updatedAt = generatedAt,
    )
}

private class TrackingAnsiblexRepository(
    private val connections: List<AnsiblexConnection>,
) : AnsiblexRepository {
    var mutated: Boolean = false
        private set

    override fun getAll(): List<AnsiblexConnection> = connections

    override fun addManual(draft: AnsiblexDraft): AnsiblexConnection = mutation()

    override fun setEnabled(id: String, enabled: Boolean): Boolean = mutation()

    override fun delete(id: String): Boolean = mutation()

    override fun clearImported(): Int = mutation()

    override fun clearAll(): Int = mutation()

    private fun <T> mutation(): T {
        mutated = true
        error("Exporter attempted to mutate the Ansiblex repository")
    }
}
