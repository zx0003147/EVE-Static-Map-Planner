package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.control.mission.MissionId
import dev.evestaticmapplanner.core.jump.CapitalJumpCandidateProvider
import dev.evestaticmapplanner.core.jump.JumpProfile
import dev.evestaticmapplanner.core.jump.UniformGridSystemPositionIndex
import dev.evestaticmapplanner.core.jump.UniverseDistanceCalculator
import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.core.map.projectionFor
import dev.evestaticmapplanner.core.model.SchematicPosition
import dev.evestaticmapplanner.core.model.SolarSystem
import dev.evestaticmapplanner.core.model.StargateConnection
import dev.evestaticmapplanner.core.model.StaticMapData
import dev.evestaticmapplanner.core.model.UniversePosition
import dev.evestaticmapplanner.core.repository.SystemSearchRepository
import dev.evestaticmapplanner.core.route.CapitalRouteEngine
import dev.evestaticmapplanner.core.route.NormalRouteEngine
import dev.evestaticmapplanner.core.route.RouteGraphBuilder
import dev.evestaticmapplanner.map.MapUiState
import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.embeddedai.AiProviderConfig
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechProfile
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.SpeechProviderProfiles
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.sde.update.SdeUpdaterPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LocalizationBehaviorInvariantTest {
    private val systems = listOf(
        system(30_000_001, "Jita", 0.0),
        system(30_000_002, "Amarr", 4.0),
        system(30_000_003, "1DQ1-A", 8.0),
        system(30_000_004, "NOL-M9", 12.0),
        system(30_000_005, "GE-8JV", 16.0),
    )

    @Test
    fun `system search and canonical names are identical across locales`() {
        val repository = FixtureSearchRepository(systems)
        val queries = listOf("Jita", "1DQ1-A", "30000004")
        val byLocale = AppLocale.entries.associateWith { locale ->
            // Resolving display text is intentionally separate from the canonical repository query.
            AppStringsCatalog.forLocale(locale).search.searchSystemPlaceholder
            queries.map { query -> repository.searchSystems(query).map { it.id to it.name } }
        }

        assertNotEquals(
            AppStringsCatalog.forLocale(AppLocale.EN_US).search.searchSystemPlaceholder,
            AppStringsCatalog.forLocale(AppLocale.ZH_CN).search.searchSystemPlaceholder,
        )
        assertEquals(byLocale.getValue(AppLocale.EN_US), byLocale.getValue(AppLocale.ZH_CN))
        assertEquals(
            listOf("Jita", "Amarr", "1DQ1-A", "NOL-M9", "GE-8JV"),
            systems.map(SolarSystem::name),
        )
    }

    @Test
    fun `normal and capital routes and jump ranges are locale invariant`() {
        val data = StaticMapData(
            systems,
            systems.zipWithNext { first, second -> StargateConnection.between(first.id, second.id) },
        )
        val graph = RouteGraphBuilder.build(data)
        val candidateProvider = CapitalJumpCandidateProvider(UniformGridSystemPositionIndex(systems))
        val profile = JumpProfile.manual(5.0)

        val outcomes = AppLocale.entries.associateWith { locale ->
            AppStringsCatalog.forLocale(locale).route.calculate
            Triple(
                NormalRouteEngine().calculate(graph, systems.first().id, systems.last().id),
                CapitalRouteEngine(candidateProvider).calculate(systems.first().id, systems.last().id, profile),
                candidateProvider.reachableFrom(systems[2].id, profile).reachableSystemIds,
            )
        }

        assertEquals(outcomes.getValue(AppLocale.EN_US), outcomes.getValue(AppLocale.ZH_CN))
    }

    @Test
    fun `projection selection focus selection and mission identity are locale invariant`() {
        val selectedId = systems[2].id
        val missionId = MissionId("mission-localization-invariant")

        val semantics = AppLocale.entries.associateWith { locale ->
            val strings = AppStringsCatalog.forLocale(locale)
            mapOf(
                "official" to projectionFor(MapProjectionId.OFFICIAL_2D).project(systems[2]),
                "real" to projectionFor(MapProjectionId.REAL_3D).project(systems[2]),
                "selection" to MapUiState(selectedSystemId = selectedId).selectedSystemId,
                "focusSystemName" to FocusSwitchedToReal3DUiMessage(systems[2].name).systemName,
                "missionId" to missionId.value,
                "projectionIdentifier" to MapProjectionId.REAL_3D.name,
                "displayOnly" to strings.map.projectionLabel(MapProjectionId.REAL_3D),
            )
        }

        val english = semantics.getValue(AppLocale.EN_US)
        val chinese = semantics.getValue(AppLocale.ZH_CN)
        assertNotEquals(english.getValue("displayOnly"), chinese.getValue("displayOnly"))
        assertEquals(english - "displayOnly", chinese - "displayOnly")
    }

    @Test
    fun `Provider Voice credentials and Static Data identifiers are locale invariant`() {
        val provider = AiProviderConfig.normalized(
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.example.com/v1",
            credentialRef = AiCredentialRef.forProvider(AiProviderType.OPENAI_COMPATIBLE),
            modelId = "deepseek/deepseek-v4-flash-0731",
        )
        val voice = VoiceConfig(
            inputProvider = VoiceInputProvider.ALIBABA,
            outputProvider = VoiceOutputProvider.ALIBABA,
            profiles = SpeechProviderProfiles(
                alibaba = AlibabaSpeechProfile(
                    sttModel = "qwen-audio-3.0-asr-flash",
                    ttsModel = "qwen-audio-3.0-tts-flash",
                    voice = "longanfengyue",
                    sttRegion = AlibabaSpeechRegion.CHINA_BEIJING,
                    ttsRegion = AlibabaSpeechRegion.CHINA_BEIJING,
                    workspaceId = "llm-fixture-workspace",
                ),
            ),
        )
        val staticBuild = 3_466_501L

        val snapshots = AppLocale.entries.associateWith { locale ->
            val strings = AppStringsCatalog.forLocale(locale)
            strings.preferences.voiceInputProvider(voice.inputProvider)
            strings.preferences.speechRegion(voice.profiles.alibaba.sttRegion)
            strings.staticData.phase(SdeUpdaterPhase.BUILDING_DATABASE, staticBuild)
            listOf(
                provider.providerType.name,
                provider.modelId,
                provider.baseUrl,
                provider.credentialRef?.value,
                voice.inputProvider.name,
                voice.outputProvider.name,
                voice.profiles.alibaba.sttModel,
                voice.profiles.alibaba.ttsModel,
                voice.profiles.alibaba.voice,
                voice.profiles.alibaba.sttRegion.name,
                voice.profiles.alibaba.ttsRegion.name,
                voice.profiles.alibaba.workspaceId,
                voice.profiles.alibaba.credentialRef.value,
                staticBuild.toString(),
                SdeUpdaterPhase.BUILDING_DATABASE.name,
            )
        }

        assertEquals(snapshots.getValue(AppLocale.EN_US), snapshots.getValue(AppLocale.ZH_CN))
        assertNotEquals(
            AppStringsCatalog.forLocale(AppLocale.EN_US).preferences.speechRegion(AlibabaSpeechRegion.CHINA_BEIJING),
            AppStringsCatalog.forLocale(AppLocale.ZH_CN).preferences.speechRegion(AlibabaSpeechRegion.CHINA_BEIJING),
        )
    }

    private fun system(id: Int, name: String, xLy: Double) = SolarSystem(
        id = id,
        constellationId = 20_000_001,
        regionId = 10_000_001,
        name = name,
        securityStatus = 0.0,
        securityClass = null,
        position = UniversePosition(
            xLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR,
            0.0,
            xLy * UniverseDistanceCalculator.METERS_PER_EVE_LIGHT_YEAR / 2.0,
        ),
        schematicPosition = SchematicPosition(xLy * 1_000_000_000_000_000.0, xLy * 2_000_000_000_000_000.0),
        radius = 1.0,
        factionId = null,
        wormholeClassId = null,
    )

    private class FixtureSearchRepository(private val systems: List<SolarSystem>) : SystemSearchRepository {
        override fun searchSystems(query: String, limit: Int): List<SolarSystem> {
            val normalized = query.trim()
            return systems.asSequence()
                .filter { it.name.contains(normalized, ignoreCase = true) || it.id.toString() == normalized }
                .take(limit)
                .toList()
        }
    }
}
