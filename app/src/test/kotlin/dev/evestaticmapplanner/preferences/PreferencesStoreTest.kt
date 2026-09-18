package dev.evestaticmapplanner.preferences

import dev.evestaticmapplanner.embeddedai.AiCredentialRef
import dev.evestaticmapplanner.embeddedai.AiProviderConfig
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.embeddedai.BRAVE_SEARCH_CREDENTIAL_REF
import dev.evestaticmapplanner.embeddedai.WebSearchConfig
import dev.evestaticmapplanner.embeddedai.VoiceConfig
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechProfile
import dev.evestaticmapplanner.embeddedai.AlibabaSpeechRegion
import dev.evestaticmapplanner.embeddedai.LocalSpeechProfile
import dev.evestaticmapplanner.embeddedai.OpenAiSpeechProfile
import dev.evestaticmapplanner.embeddedai.SpeechProviderProfiles
import dev.evestaticmapplanner.embeddedai.VoiceInputProvider
import dev.evestaticmapplanner.embeddedai.VoiceOutputProvider
import dev.evestaticmapplanner.localization.AppLocale
import dev.evestaticmapplanner.localization.AppLocaleDetector
import dev.evestaticmapplanner.localization.SystemLocaleSource
import dev.evestaticmapplanner.shortcut.KeyboardShortcut
import dev.evestaticmapplanner.shortcut.ShortcutKey
import dev.evestaticmapplanner.shortcut.ShortcutModifier
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.evestaticmapplanner.preferences.MiniMapFollowMode
import dev.evestaticmapplanner.preferences.MiniMapPreferences
import dev.evestaticmapplanner.preferences.MiniMapWindowBounds

class PreferencesStoreTest {
    @Test
    fun `AI provider settings round trip without writing an API Key`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val secretMarker = "SECRET_SHOULD_NEVER_APPEAR_12345"
        val config = AiProviderConfig.normalized(
            providerType = AiProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.example.com/v1",
            credentialRef = AiCredentialRef("openai-compatible"),
            modelId = "example-model",
            temperature = 0.35,
            requestTimeoutSeconds = 42,
        )

        PropertiesPreferencesStore(path).save(AppPreferences(aiProvider = config))

        val text = Files.readString(path)
        assertTrue(text.contains("aiProvider.type=OPENAI_COMPATIBLE"))
        assertTrue(text.contains("aiProvider.credentialRef=openai-compatible"))
        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains(secretMarker))
        assertEquals(config, PropertiesPreferencesStore(path).load().aiProvider)
    }

    @Test
    fun `version one settings migrate without discarding existing preferences`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(
            path,
            "settings.version=1\nmapDisplay.systemZoomThreshold=9.0\nmarker.showMarkers=false\n",
        )

        val loaded = PropertiesPreferencesStore(path).load()

        assertEquals(9.0, loaded.mapDisplay.systemZoomThreshold)
        assertFalse(loaded.marker.showMarkers)
        assertNull(loaded.aiProvider)
    }

    @Test
    fun `Phase 3 version two AI settings migrate without changing the credential reference`() =
        withTemporaryDirectory { root ->
            val path = root.resolve("settings.properties")
            Files.writeString(
                path,
                """
                settings.version=2
                aiProvider.type=OPENROUTER
                aiProvider.credentialRef=openrouter
                aiProvider.modelId=deepseek/deepseek-v4-flash-0731
                aiProvider.temperature=0.2
                aiProvider.requestTimeoutSeconds=90
                """.trimIndent(),
            )

            val store = PropertiesPreferencesStore(path)
            val migrated = store.load()
            val active = checkNotNull(migrated.aiProvider)

            assertEquals(AiProviderType.OPENROUTER, active.providerType)
            assertEquals(AiCredentialRef("openrouter"), active.credentialRef)
            assertEquals(active, migrated.aiProviderProfiles[AiProviderType.OPENROUTER])

            store.save(migrated)
            val reloaded = store.load()
            assertTrue(Files.readString(path).lineSequence().any { it == "settings.version=8" })
            assertEquals(AiCredentialRef("openrouter"), reloaded.aiProvider?.credentialRef)
            assertEquals(active, reloaded.aiProviderProfiles[AiProviderType.OPENROUTER])
        }

    @Test
    fun `multiple provider profiles round trip independently while one remains active`() =
        withTemporaryDirectory { root ->
            val path = root.resolve("settings.properties")
            val openRouter = AiProviderConfig.DefaultOpenRouter
            val anthropic = AiProviderConfig.normalized(
                providerType = AiProviderType.ANTHROPIC,
                baseUrl = null,
                modelId = "claude-fixture",
                temperature = 0.4,
                requestTimeoutSeconds = 70,
            )
            val compatible = AiProviderConfig.normalized(
                providerType = AiProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://api.example.com/v1",
                modelId = "compatible-fixture",
            )
            val expected = AppPreferences(
                aiProvider = anthropic,
                aiProviderProfiles = mapOf(
                    AiProviderType.OPENROUTER to openRouter,
                    AiProviderType.ANTHROPIC to anthropic,
                    AiProviderType.OPENAI_COMPATIBLE to compatible,
                ),
            )

            PropertiesPreferencesStore(path).save(expected)
            val loaded = PropertiesPreferencesStore(path).load()

            assertEquals(anthropic, loaded.aiProvider)
            assertEquals(expected.aiProviderProfiles, loaded.aiProviderProfiles)
            assertTrue(Files.readString(path).contains("aiProviderProfiles.OPENROUTER.modelId"))
            assertTrue(Files.readString(path).contains("aiProviderProfiles.ANTHROPIC.modelId"))
            assertTrue(Files.readString(path).contains("aiProviderProfiles.OPENAI_COMPATIBLE.baseUrl"))
        }

    @Test
    fun `mini-map settings round trip including negative monitor coordinates`() {
        val root = createTempDirectory("mini-map-preferences")
        val path = root.resolve("settings.properties")
        val expected = MiniMapPreferences(
            enabled = true,
            stargateHops = 5,
            followMode = MiniMapFollowMode.PINNED,
            pinnedCharacterId = 90_000_001,
            windowBounds = MiniMapWindowBounds(-900f, 125f, 510f, 390f),
            includeAnsiblexEdges = true,
            windowStyle = MiniMapWindowStyle.HUD,
            interactionMode = MiniMapInteractionMode.HUD_LOCKED,
            hudOpacity = 0.64f,
            snapToScreenEdges = false,
        )
        val store = PropertiesPreferencesStore(path)
        store.save(AppPreferences.Defaults.copy(miniMap = expected))

        assertEquals(expected, store.load().miniMap)
        root.toFile().deleteRecursively()
    }

    @Test
    fun `invalid Mini-map HUD mode falls back to Standard and Interactive`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(
            path,
            """
            settings.version=1
            miniMap.window.style=GLASS
            miniMap.interaction.mode=HUD_LOCKED
            miniMap.hud.opacity=not-a-number
            """.trimIndent(),
        )

        val loaded = PropertiesPreferencesStore(path).load().miniMap

        assertEquals(MiniMapWindowStyle.STANDARD, loaded.windowStyle)
        assertEquals(MiniMapInteractionMode.INTERACTIVE, loaded.interactionMode)
        assertEquals(0.88f, loaded.hudOpacity)
    }
    @Test
    fun `Shared Map settings persist only non-sensitive configuration`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val preferences = AppPreferences(
            sharedMap = SharedMapPreferences(
                serverUrl = "https://map.example.com",
                selectedWorkspaceId = "01991d60-b8a2-7a20-a311-b5114b27c219",
                deviceName = "FC Laptop",
            ),
        )

        PropertiesPreferencesStore(path).save(preferences)

        val text = Files.readString(path)
        assertTrue(text.contains("sharedMap.serverUrl=https\\://map.example.com"))
        assertTrue(text.contains("sharedMap.selectedWorkspaceId=01991d60-b8a2-7a20-a311-b5114b27c219"))
        assertTrue(text.contains("sharedMap.deviceName=FC Laptop"))
        assertFalse(text.contains("accessToken", ignoreCase = true))
        assertFalse(text.contains("esm_dev_"))
        assertEquals(preferences.sharedMap, PropertiesPreferencesStore(path).load().sharedMap)
    }

    @Test
    fun `default AppPreferences contains default MapDisplayPreferences`() {
        assertEquals(MapDisplayPreferences.Defaults, AppPreferences.Defaults.mapDisplay)
        assertEquals(2.0, MapDisplayPreferences.Defaults.constellationZoomThreshold)
        assertEquals(6.0, MapDisplayPreferences.Defaults.systemZoomThreshold)
        assertEquals(1.8, MapDisplayPreferences.Defaults.real3DConstellationScaleThreshold)
        assertEquals(4.5, MapDisplayPreferences.Defaults.real3DSystemScaleThreshold)
        assertTrue(MapDisplayPreferences.Defaults.real3DStargateVisibilityFilteringEnabled)
        assertEquals(16f, MapDisplayPreferences.Defaults.regionPrimaryFontSizeSp)
        assertEquals(20f, MapDisplayPreferences.Defaults.regionBackgroundFontSizeSp)
        assertEquals(0.07f, MapDisplayPreferences.Defaults.regionBackgroundAlpha)
        assertEquals(13f, MapDisplayPreferences.Defaults.constellationFontSizeSp)
        assertEquals(11f, MapDisplayPreferences.Defaults.systemFontSizeSp)
        assertEquals(0.75, MapDisplayPreferences.Defaults.sovereigntyLogoEmphasisZoom)
        assertEquals(MarkerPreferences.Defaults, AppPreferences.Defaults.marker)
        assertTrue(MarkerPreferences.Defaults.showMarkers)
        assertTrue(MarkerPreferences.Defaults.showSharedMarkers)
        assertTrue(MarkerPreferences.Defaults.showMarkerNames)
        assertEquals(13f, MarkerPreferences.Defaults.savedMarkerAppearance.ringRadiusDp)
        assertEquals(2f, MarkerPreferences.Defaults.savedMarkerAppearance.lineWidthDp)
        assertTrue(MarkerPreferences.Defaults.savedMarkerAppearance.glowEnabled)
        assertEquals(0.5f, MarkerPreferences.Defaults.savedMarkerAppearance.glowStrength)
        assertFalse(AppPreferences.Defaults.aiControl.enabled)
        assertFalse(AppPreferences.Defaults.aiControl.savedMarkerAccessEnabled)
    }

    @Test
    fun `save writes version eight and a new store reloads all values`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val expected = AppPreferences(
            mapDisplay = MapDisplayPreferences(
                constellationZoomThreshold = 3.25,
                systemZoomThreshold = 9.5,
                real3DConstellationScaleThreshold = 2.25,
                real3DSystemScaleThreshold = 7.5,
                real3DStargateVisibilityFilteringEnabled = false,
                regionPrimaryFontSizeSp = 17f,
                regionBackgroundFontSizeSp = 22f,
                regionBackgroundAlpha = 0.12f,
                constellationFontSizeSp = 14f,
                systemFontSizeSp = 12f,
                sovereigntyLogoEmphasisZoom = 0.85,
            ),
            marker = MarkerPreferences(
                showMarkers = false,
                showSharedMarkers = false,
                showMarkerNames = false,
                savedMarkerAppearance = SavedMarkerAppearancePreferences(
                    ringRadiusDp = 24.5f,
                    lineWidthDp = 3.5f,
                    glowEnabled = false,
                    glowStrength = 0.8f,
                ),
            ),
            aiControl = AiControlPreferences(enabled = true, savedMarkerAccessEnabled = true),
            webSearch = WebSearchConfig(enabled = true),
            overlayVisibility = OverlayVisibilityPreferences(
                disabledLayers = setOf(
                    OverlayLayerKey("fixture.provider", "second"),
                    OverlayLayerKey("fixture.provider", "first"),
                ),
            ),
        )

        PropertiesPreferencesStore(path).save(expected)

        assertTrue(Files.readString(path).lineSequence().any { it == "settings.version=8" })
        assertTrue(Files.readString(path).lineSequence().any { it == "marker.showMarkers=false" })
        assertTrue(Files.readString(path).lineSequence().any { it == "marker.showSharedMarkers=false" })
        assertTrue(Files.readString(path).lineSequence().any { it == "marker.savedMarkerAppearance.ringRadiusDp=24.5" })
        assertTrue(Files.readString(path).lineSequence().any { it == "marker.savedMarkerAppearance.glowEnabled=false" })
        assertTrue(Files.readString(path).lineSequence().any { it == "aiControl.enabled=true" })
        assertTrue(Files.readString(path).lineSequence().any { it == "aiControl.savedMarkerAccessEnabled=true" })
        assertTrue(Files.readString(path).lineSequence().any { it == "webSearch.enabled=true" })
        assertTrue(Files.readString(path).lineSequence().any { it == "webSearch.credentialRef=brave-search" })
        assertTrue(Files.readString(path).lineSequence().any {
            it == "mapDisplay.sovereigntyLogoEmphasisZoom=0.85"
        })
        assertTrue(Files.readString(path).lineSequence().any {
            it == "mapDisplay.real3DConstellationScaleThreshold=2.25"
        })
        assertTrue(Files.readString(path).lineSequence().any {
            it == "mapDisplay.real3DSystemScaleThreshold=7.5"
        })
        assertTrue(Files.readString(path).lineSequence().any {
            it == "mapDisplay.real3DStargateVisibilityFilteringEnabled=false"
        })
        assertTrue(Files.readString(path).lineSequence().any {
            it == "overlay.disabledLayers=fixture.provider/first,fixture.provider/second"
        })
        assertEquals(expected, PropertiesPreferencesStore(path).load())

        PropertiesPreferencesStore(path).save(
            expected.copy(aiControl = AiControlPreferences(enabled = false, savedMarkerAccessEnabled = false)),
        )
        val disabled = PropertiesPreferencesStore(path).load().aiControl
        assertFalse(disabled.enabled)
        assertFalse(disabled.savedMarkerAccessEnabled)
    }

    @Test
    fun `Web Search settings round trip only the DPAPI reference and never a Brave Key`() =
        withTemporaryDirectory { root ->
            val path = root.resolve("settings.properties")
            val marker = "BRAVE_SECRET_MUST_NOT_BE_WRITTEN"
            val expected = WebSearchConfig(enabled = true, credentialRef = BRAVE_SEARCH_CREDENTIAL_REF)

            PropertiesPreferencesStore(path).save(AppPreferences(webSearch = expected))

            val text = Files.readString(path)
            assertTrue(text.contains("webSearch.enabled=true"))
            assertTrue(text.contains("webSearch.credentialRef=brave-search"))
            assertFalse(text.contains(marker))
            assertFalse(text.contains("BRAVE_SEARCH_API_KEY"))
            assertEquals(expected, PropertiesPreferencesStore(path).load().webSearch)
        }

    @Test
    fun `Voice settings round trip only the DPAPI reference and never a Key`() =
        withTemporaryDirectory { root ->
            val path = root.resolve("settings.properties")
            val expected = VoiceConfig(
                inputProvider = VoiceInputProvider.LOCAL,
                outputProvider = VoiceOutputProvider.OPENAI,
                autoSendAfterTranscription = true,
                readAssistantRepliesAloud = true,
                profiles = SpeechProviderProfiles(
                    local = LocalSpeechProfile("Fixture Voice", 2, 80),
                    openAi = OpenAiSpeechProfile(voice = "coral"),
                    alibaba = AlibabaSpeechProfile(
                        sttModel = "qwen3-asr-flash-2026-02-10",
                        ttsModel = "qwen-audio-3.0-tts-flash",
                        voice = "longanhuan_v3.6",
                        sttRegion = AlibabaSpeechRegion.SINGAPORE,
                        ttsRegion = AlibabaSpeechRegion.CHINA_BEIJING,
                        workspaceId = "fixture-workspace",
                        sttTimeoutSeconds = 45,
                        ttsTimeoutSeconds = 75,
                    ),
                ),
            )

            PropertiesPreferencesStore(path).save(AppPreferences(voice = expected))

            val text = Files.readString(path)
            assertTrue(text.contains("voice.inputProvider=LOCAL"))
            assertTrue(text.contains("voice.outputProvider=OPENAI"))
            assertTrue(text.contains("voice.credentialRef=openai-voice"))
            assertTrue(text.contains("voice.alibaba.credentialRef=alibaba-speech"))
            assertTrue(text.contains("voice.alibaba.sttModel=qwen3-asr-flash-2026-02-10"))
            assertTrue(text.contains("voice.alibaba.workspaceId=fixture-workspace"))
            assertFalse(text.contains("OPENAI_VOICE_API_KEY"))
            assertFalse(text.contains("DASHSCOPE_API_KEY"))
            assertEquals(expected, PropertiesPreferencesStore(path).load().voice)
        }

    @Test
    fun `atomic replacement leaves no temporary settings file`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val store = PropertiesPreferencesStore(path)
        store.save(AppPreferences(MapDisplayPreferences(regionPrimaryFontSizeSp = 18f)))
        val replacement = AppPreferences(MapDisplayPreferences(regionPrimaryFontSizeSp = 24f))

        store.save(replacement)

        assertEquals(replacement, store.load())
        Files.list(root).use { files ->
            assertEquals(listOf("settings.properties"), files.map { it.name }.sorted().toList())
        }
    }

    @Test
    fun `malformed values fall back individually while unknown keys are ignored`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(
            path,
            """
            settings.version=1
            mapDisplay.constellationZoomThreshold=broken
            mapDisplay.systemZoomThreshold=9.0
            mapDisplay.regionPrimaryFontSizeSp=18
            mapDisplay.regionBackgroundFontSizeSp=NaN
            mapDisplay.regionBackgroundAlpha=0.12
            mapDisplay.constellationFontSizeSp=15
            mapDisplay.systemFontSizeSp=12
            mapDisplay.sovereigntyLogoEmphasisZoom=not-a-mode
            mapDisplay.real3DStargateVisibilityFilteringEnabled=invalid
            future.unknown.preference=ignored
            """.trimIndent(),
        )

        val loaded = PropertiesPreferencesStore(path).load().mapDisplay

        assertEquals(2.0, loaded.constellationZoomThreshold)
        assertEquals(9.0, loaded.systemZoomThreshold)
        assertEquals(18f, loaded.regionPrimaryFontSizeSp)
        assertEquals(20f, loaded.regionBackgroundFontSizeSp)
        assertEquals(0.12f, loaded.regionBackgroundAlpha)
        assertEquals(15f, loaded.constellationFontSizeSp)
        assertEquals(12f, loaded.systemFontSizeSp)
        assertEquals(0.75, loaded.sovereigntyLogoEmphasisZoom)
        assertTrue(loaded.real3DStargateVisibilityFilteringEnabled)
    }

    @Test
    fun `Sovereignty logo emphasis zoom rejects non-finite and out-of-range values`() {
        listOf(
            0.0,
            -0.5,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            0.009,
            250.01,
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                MapDisplayPreferences(sovereigntyLogoEmphasisZoom = invalid)
            }
        }

        assertEquals(0.01, MapDisplayPreferences(sovereigntyLogoEmphasisZoom = 0.01).sovereigntyLogoEmphasisZoom)
        assertEquals(250.0, MapDisplayPreferences(sovereigntyLogoEmphasisZoom = 250.0).sovereigntyLogoEmphasisZoom)
    }

    @Test
    fun `invalid stored Sovereignty logo emphasis zoom falls back to default`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        listOf("0", "-1", "NaN", "Infinity", "0.009", "250.01").forEach { invalid ->
            Files.writeString(
                path,
                "settings.version=1\nmapDisplay.sovereigntyLogoEmphasisZoom=$invalid\n",
            )

            assertEquals(
                0.75,
                PropertiesPreferencesStore(path).load().mapDisplay.sovereigntyLogoEmphasisZoom,
            )
        }
    }

    @Test
    fun `invalid threshold ordering falls back without discarding valid visual values`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(
            path,
            """
            settings.version=1
            mapDisplay.constellationZoomThreshold=8
            mapDisplay.systemZoomThreshold=6
            mapDisplay.regionPrimaryFontSizeSp=19
            """.trimIndent(),
        )

        val loaded = PropertiesPreferencesStore(path).load().mapDisplay

        assertEquals(2.0, loaded.constellationZoomThreshold)
        assertEquals(6.0, loaded.systemZoomThreshold)
        assertEquals(19f, loaded.regionPrimaryFontSizeSp)
    }

    @Test
    fun `malformed marker booleans fall back independently under version one`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(
            path,
            "settings.version=1\nmarker.showMarkers=false\nmarker.showSharedMarkers=invalid\n" +
                "marker.showMarkerNames=not-a-boolean\n",
        )

        val loaded = PropertiesPreferencesStore(path).load().marker

        assertFalse(loaded.showMarkers)
        assertTrue(loaded.showSharedMarkers)
        assertTrue(loaded.showMarkerNames)
        assertEquals(SavedMarkerAppearancePreferences.Defaults, loaded.savedMarkerAppearance)
    }

    @Test
    fun `invalid saved marker appearance values fall back independently under version one`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(
            path,
            """
            settings.version=1
            marker.savedMarkerAppearance.ringRadiusDp=999
            marker.savedMarkerAppearance.lineWidthDp=-4
            marker.savedMarkerAppearance.glowEnabled=not-a-boolean
            marker.savedMarkerAppearance.glowStrength=NaN
            """.trimIndent(),
        )

        assertEquals(
            SavedMarkerAppearancePreferences.Defaults,
            PropertiesPreferencesStore(path).load().marker.savedMarkerAppearance,
        )
    }

    @Test
    fun `invalid overlay visibility values fall back individually to enabled`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(
            path,
            "settings.version=1\noverlay.disabledLayers=fixture.provider/valid,bad,UPPER/layer,a/b/c,/missing\n",
        )

        val loaded = PropertiesPreferencesStore(path).load().overlayVisibility

        assertEquals(setOf(OverlayLayerKey("fixture.provider", "valid")), loaded.disabledLayers)
        assertFalse(loaded.isEnabled(OverlayLayerKey("fixture.provider", "valid")))
        assertTrue(loaded.isEnabled(OverlayLayerKey("fixture.provider", "other")))
    }

    @Test
    fun `missing and invalid AI Control preference fail safely to disabled`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(path, "settings.version=1\nmarker.showMarkers=true\n")
        assertFalse(PropertiesPreferencesStore(path).load().aiControl.enabled)

        val warnings = mutableListOf<String>()
        Files.writeString(path, "settings.version=1\naiControl.enabled=TRUE\n")
        val loaded = PropertiesPreferencesStore(path, warnings::add).load()

        assertFalse(loaded.aiControl.enabled)
        assertEquals(listOf("AI Control preference is invalid and was disabled"), warnings)
    }

    @Test
    fun `missing and invalid AI Saved Marker access fail safely to disabled`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(path, "settings.version=1\naiControl.enabled=true\n")
        val oldSettings = PropertiesPreferencesStore(path).load().aiControl
        assertTrue(oldSettings.enabled)
        assertFalse(oldSettings.savedMarkerAccessEnabled)

        val warnings = mutableListOf<String>()
        Files.writeString(path, "settings.version=1\naiControl.savedMarkerAccessEnabled=TRUE\n")
        val malformed = PropertiesPreferencesStore(path, warnings::add).load().aiControl
        assertFalse(malformed.savedMarkerAccessEnabled)
        assertEquals(listOf("AI Saved Marker access preference is invalid and was disabled"), warnings)
    }

    @Test
    fun `AI Saved Marker access persists ON and OFF independently from AI Control`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val store = PropertiesPreferencesStore(path)

        store.save(AppPreferences(aiControl = AiControlPreferences(enabled = false, savedMarkerAccessEnabled = true)))
        val enabled = PropertiesPreferencesStore(path).load().aiControl
        assertFalse(enabled.enabled)
        assertTrue(enabled.savedMarkerAccessEnabled)

        store.save(AppPreferences(aiControl = AiControlPreferences(enabled = true, savedMarkerAccessEnabled = false)))
        val disabled = PropertiesPreferencesStore(path).load().aiControl
        assertTrue(disabled.enabled)
        assertFalse(disabled.savedMarkerAccessEnabled)
    }

    @Test
    fun `unsupported or missing settings version safely uses defaults`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(path, "settings.version=99\nmapDisplay.systemZoomThreshold=12\n")
        assertEquals(AppPreferences.Defaults, PropertiesPreferencesStore(path).load())

        Files.writeString(path, "mapDisplay.systemZoomThreshold=12\n")
        assertEquals(AppPreferences.Defaults, PropertiesPreferencesStore(path).load())
    }

    @Test
    fun `locale preference round trips as a stable tag and survives restart`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val expected = AppPreferences.Defaults.copy(
            uiLocale = AppLocale.ZH_CN,
            mapDisplay = MapDisplayPreferences.Defaults.copy(systemZoomThreshold = 8.0),
            aiControl = AiControlPreferences(enabled = true, savedMarkerAccessEnabled = true),
        )

        PropertiesPreferencesStore(path).save(expected)
        val savedText = Files.readString(path)
        val restarted = PropertiesPreferencesStore(path).load()

        assertTrue(savedText.lineSequence().any { it == "settings.version=8" })
        assertTrue(savedText.lineSequence().any { it == "ui.locale=zh-CN" })
        assertEquals(expected, restarted)
    }

    @Test
    fun `versions one through six without locale preserve English upgrade behavior`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val chineseDetector = localeDetector("zh-CN")

        (1..6).forEach { version ->
            Files.writeString(
                path,
                "settings.version=$version\nmapDisplay.systemZoomThreshold=8.0\n",
            )

            val loaded = PropertiesPreferencesStore(path, appLocaleDetector = chineseDetector).load()
            assertEquals(AppLocale.EN_US, loaded.uiLocale, "settings v$version")
            assertEquals(8.0, loaded.mapDisplay.systemZoomThreshold, "settings v$version")
        }
    }

    @Test
    fun `invalid version seven locale safely falls back to English`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(path, "settings.version=7\nui.locale=zh-Hans\n")

        val loaded = PropertiesPreferencesStore(
            path,
            appLocaleDetector = localeDetector("zh-CN"),
        ).load()

        assertEquals(AppLocale.EN_US, loaded.uiLocale)
    }

    @Test
    fun `versions one through seven load with no Push-to-Talk shortcut`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        (1..7).forEach { version ->
            Files.writeString(path, "settings.version=$version\nmapDisplay.systemZoomThreshold=8.0\n")
            assertNull(PropertiesPreferencesStore(path).load().pushToTalkShortcut, "settings v$version")
        }
    }

    @Test
    fun `Push-to-Talk shortcut round trips in version eight and clear removes the property`() =
        withTemporaryDirectory { root ->
            val path = root.resolve("settings.properties")
            val store = PropertiesPreferencesStore(path)
            val shortcut = KeyboardShortcut(
                ShortcutKey.SPACE,
                setOf(ShortcutModifier.CTRL, ShortcutModifier.ALT),
            )

            store.save(AppPreferences.Defaults.copy(pushToTalkShortcut = shortcut))

            assertEquals(shortcut, store.load().pushToTalkShortcut)
            assertTrue(Files.readString(path).contains("voice.pushToTalkShortcut=kbd\\:v1\\:CTRL+ALT+SPACE"))

            store.save(store.load().copy(pushToTalkShortcut = null))

            assertNull(store.load().pushToTalkShortcut)
            assertFalse(Files.readString(path).contains(KEY_VOICE_PUSH_TO_TALK_SHORTCUT))
        }

    @Test
    fun `invalid Push-to-Talk shortcut falls back alone and reports a warning`() =
        withTemporaryDirectory { root ->
            val path = root.resolve("settings.properties")
            val warnings = mutableListOf<String>()
            Files.writeString(
                path,
                "settings.version=8\nvoice.pushToTalkShortcut=kbd:v2:UNKNOWN\nmarker.showMarkers=false\n",
            )

            val loaded = PropertiesPreferencesStore(path, warnings::add).load()

            assertNull(loaded.pushToTalkShortcut)
            assertFalse(loaded.marker.showMarkers)
            assertEquals(listOf("Push-to-Talk shortcut is invalid and was ignored"), warnings)
        }

    @Test
    fun `fresh install selects Simplified Chinese for Chinese system locales without global mutation`() =
        withTemporaryDirectory { root ->
            val originalJvmLocale = Locale.getDefault()
            listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hant-TW").forEachIndexed { index, tag ->
                val loaded = PropertiesPreferencesStore(
                    root.resolve("settings-$index.properties"),
                    appLocaleDetector = localeDetector(tag),
                ).load()

                assertEquals(AppLocale.ZH_CN, loaded.uiLocale, tag)
            }
            assertEquals(originalJvmLocale, Locale.getDefault())
        }

    @Test
    fun `fresh install selects English for non Chinese system locale`() = withTemporaryDirectory { root ->
        val loaded = PropertiesPreferencesStore(
            root.resolve("settings.properties"),
            appLocaleDetector = localeDetector("fr-FR"),
        ).load()

        assertEquals(AppLocale.EN_US, loaded.uiLocale)
    }

    @Test
    fun `reset to defaults is persisted`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val store = PropertiesPreferencesStore(path)
        store.save(AppPreferences(MapDisplayPreferences(
            systemZoomThreshold = 12.0,
            sovereigntyLogoEmphasisZoom = 0.85,
        )))

        assertEquals(AppPreferences.Defaults, store.resetToDefaults())
        assertEquals(AppPreferences.Defaults, PropertiesPreferencesStore(path).load())
        assertEquals(0.75, PropertiesPreferencesStore(path).load().mapDisplay.sovereigntyLogoEmphasisZoom)
        assertFalse(PropertiesPreferencesStore(path).load().aiControl.enabled)
        assertFalse(PropertiesPreferencesStore(path).load().aiControl.savedMarkerAccessEnabled)
    }

    @Test
    fun `preferences persistence never writes static or user databases`() = withTemporaryDirectory { root ->
        val staticDatabase = root.resolve("static.db")
        val userDatabase = root.resolve("user.db")
        val staticBytes = byteArrayOf(1, 2, 3, 4)
        val userBytes = byteArrayOf(5, 6, 7, 8)
        Files.write(staticDatabase, staticBytes)
        Files.write(userDatabase, userBytes)

        PropertiesPreferencesStore(root.resolve("settings.properties")).save(
            AppPreferences(MapDisplayPreferences(regionBackgroundAlpha = 0.15f)),
        )

        assertContentEquals(staticBytes, Files.readAllBytes(staticDatabase))
        assertContentEquals(userBytes, Files.readAllBytes(userDatabase))
        assertFalse(Files.isDirectory(staticDatabase))
        assertFalse(Files.isDirectory(userDatabase))
    }

    private fun localeDetector(tag: String): AppLocaleDetector = AppLocaleDetector(SystemLocaleSource { tag })
}

private inline fun withTemporaryDirectory(block: (java.nio.file.Path) -> Unit) {
    val directory = createTempDirectory("preferences-test-")
    try {
        block(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}
