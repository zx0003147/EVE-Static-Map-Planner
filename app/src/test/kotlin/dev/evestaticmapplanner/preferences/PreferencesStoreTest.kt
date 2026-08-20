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
    }

    @Test
    fun `save writes version one and a new store reloads all values`() = withTemporaryDirectory { root ->
        val path = root.resolve("settings.properties")
        val expected = AppPreferences(
            MapDisplayPreferences(
                constellationZoomThreshold = 3.25,
                systemZoomThreshold = 9.5,
                regionPrimaryFontSizeSp = 17f,
                regionBackgroundFontSizeSp = 22f,
                regionBackgroundAlpha = 0.12f,
                constellationFontSizeSp = 14f,
                systemFontSizeSp = 12f,
            ),
        )

        PropertiesPreferencesStore(path).save(expected)

        assertTrue(Files.readString(path).lineSequence().any { it == "settings.version=1" })
        assertEquals(expected, PropertiesPreferencesStore(path).load())
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
