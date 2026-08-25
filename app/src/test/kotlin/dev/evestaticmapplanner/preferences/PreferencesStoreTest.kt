package dev.evestaticmapplanner.preferences

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreferencesStoreTest {
    @Test
    fun `default AppPreferences contains default MapDisplayPreferences`() {
        assertEquals(MapDisplayPreferences.Defaults, AppPreferences.Defaults.mapDisplay)
        assertEquals(2.0, MapDisplayPreferences.Defaults.constellationZoomThreshold)
        assertEquals(6.0, MapDisplayPreferences.Defaults.systemZoomThreshold)
        assertEquals(16f, MapDisplayPreferences.Defaults.regionPrimaryFontSizeSp)
        assertEquals(20f, MapDisplayPreferences.Defaults.regionBackgroundFontSizeSp)
        assertEquals(0.07f, MapDisplayPreferences.Defaults.regionBackgroundAlpha)
        assertEquals(13f, MapDisplayPreferences.Defaults.constellationFontSizeSp)
        assertEquals(11f, MapDisplayPreferences.Defaults.systemFontSizeSp)
        assertEquals(MarkerPreferences.Defaults, AppPreferences.Defaults.marker)
        assertTrue(MarkerPreferences.Defaults.showMarkers)
        assertTrue(MarkerPreferences.Defaults.showMarkerNames)
        assertEquals(13f, MarkerPreferences.Defaults.savedMarkerAppearance.ringRadiusDp)
        assertEquals(2f, MarkerPreferences.Defaults.savedMarkerAppearance.lineWidthDp)
        assertTrue(MarkerPreferences.Defaults.savedMarkerAppearance.glowEnabled)
        assertEquals(0.5f, MarkerPreferences.Defaults.savedMarkerAppearance.glowStrength)
        assertFalse(AppPreferences.Defaults.aiControl.enabled)
    }

    @Test
    fun `save writes version one and a new store reloads all values`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val expected = AppPreferences(
            mapDisplay = MapDisplayPreferences(
                constellationZoomThreshold = 3.25,
                systemZoomThreshold = 9.5,
                regionPrimaryFontSizeSp = 17f,
                regionBackgroundFontSizeSp = 22f,
                regionBackgroundAlpha = 0.12f,
                constellationFontSizeSp = 14f,
                systemFontSizeSp = 12f,
            ),
            marker = MarkerPreferences(
                showMarkers = false,
                showMarkerNames = false,
                savedMarkerAppearance = SavedMarkerAppearancePreferences(
                    ringRadiusDp = 24.5f,
                    lineWidthDp = 3.5f,
                    glowEnabled = false,
                    glowStrength = 0.8f,
                ),
            ),
            aiControl = AiControlPreferences(enabled = true),
            overlayVisibility = OverlayVisibilityPreferences(
                disabledLayers = setOf(
                    OverlayLayerKey("fixture.provider", "second"),
                    OverlayLayerKey("fixture.provider", "first"),
                ),
            ),
        )

        PropertiesPreferencesStore(path).save(expected)

        assertTrue(Files.readString(path).lineSequence().any { it == "settings.version=1" })
        assertTrue(Files.readString(path).lineSequence().any { it == "marker.showMarkers=false" })
        assertTrue(Files.readString(path).lineSequence().any { it == "marker.savedMarkerAppearance.ringRadiusDp=24.5" })
        assertTrue(Files.readString(path).lineSequence().any { it == "marker.savedMarkerAppearance.glowEnabled=false" })
        assertTrue(Files.readString(path).lineSequence().any { it == "aiControl.enabled=true" })
        assertTrue(Files.readString(path).lineSequence().any {
            it == "overlay.disabledLayers=fixture.provider/first,fixture.provider/second"
        })
        assertEquals(expected, PropertiesPreferencesStore(path).load())

        PropertiesPreferencesStore(path).save(expected.copy(aiControl = AiControlPreferences(enabled = false)))
        assertFalse(PropertiesPreferencesStore(path).load().aiControl.enabled)
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
            "settings.version=1\nmarker.showMarkers=false\nmarker.showMarkerNames=not-a-boolean\n",
        )

        val loaded = PropertiesPreferencesStore(path).load().marker

        assertFalse(loaded.showMarkers)
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
    fun `unsupported or missing settings version safely uses defaults`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        Files.writeString(path, "settings.version=2\nmapDisplay.systemZoomThreshold=12\n")
        assertEquals(AppPreferences.Defaults, PropertiesPreferencesStore(path).load())

        Files.writeString(path, "mapDisplay.systemZoomThreshold=12\n")
        assertEquals(AppPreferences.Defaults, PropertiesPreferencesStore(path).load())
    }

    @Test
    fun `reset to defaults is persisted`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val store = PropertiesPreferencesStore(path)
        store.save(AppPreferences(MapDisplayPreferences(systemZoomThreshold = 12.0)))

        assertEquals(AppPreferences.Defaults, store.resetToDefaults())
        assertEquals(AppPreferences.Defaults, PropertiesPreferencesStore(path).load())
        assertFalse(PropertiesPreferencesStore(path).load().aiControl.enabled)
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
}

private inline fun withTemporaryDirectory(block: (java.nio.file.Path) -> Unit) {
    val directory = createTempDirectory("preferences-test-")
    try {
        block(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}
