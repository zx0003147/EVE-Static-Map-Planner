package dev.evestaticmapplanner.localization

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopHardcodedStringAuditTest {
    @Test
    fun `localized desktop surfaces do not reintroduce known shell literals`() {
        val forbiddenByFile = mapOf(
            "Main.kt" to listOf("Clear temporary markers?", "Saved markers will not be changed."),
            "preferences/FeatureSettingsWindows.kt" to listOf("Mini-map Settings", "Marker Settings", "Text(\"Close\")"),
            "ai/EmbeddedAiAssistantWindow.kt" to listOf(
                "Text(\"Embedded AI Assistant\")",
                "Text(\"Chats\")",
                "Text(\"Read aloud\")",
                "Text(\"Message…\")",
            ),
            "marker/MarkerManagerWindow.kt" to listOf("Text(\"Marker Manager\")", "Text(\"Delete Marker\")"),
            "shared/SharedMarkerManagerWindow.kt" to listOf("Text(\"Shared Marker Manager\")", "Text(\"Members\")"),
            "wormhole/WormholeManagerDialog.kt" to listOf("Text(\"Wormhole Manager\")", "Text(\"Add Wormhole\")"),
            "ansiblex/AnsiblexManagerDialog.kt" to listOf("Text(\"Ansiblex Manager\")", "Text(\"Clear Imported\")"),
            "minimap/MiniMapWindow.kt" to listOf(
                "title = \"EVE Mini-map\"",
                "Text(\"No tracked characters\")",
                "Text(\"Diagnostics\")",
                "contentDescription = \"Mini-map options\"",
            ),
        )
        val sourceRoot = sequenceOf(
            Path.of("app", "src", "main", "kotlin", "dev", "evestaticmapplanner"),
            Path.of("src", "main", "kotlin", "dev", "evestaticmapplanner"),
        ).first(Files::isDirectory)

        val violations = forbiddenByFile.flatMap { (relative, forbidden) ->
            val text = Files.readString(sourceRoot.resolve(relative))
            forbidden.filter(text::contains).map { "$relative: $it" }
        }

        assertTrue(violations.isEmpty(), "Known user-facing literals escaped localization:\n${violations.joinToString("\n")}")
    }
}
