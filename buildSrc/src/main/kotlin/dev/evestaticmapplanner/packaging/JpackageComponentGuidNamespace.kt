package dev.evestaticmapplanner.packaging

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

data class ComponentGuidMapping(
    val componentId: String,
    val effectiveOriginalGuid: String,
    val namespacedGuid: String,
)

data class ComponentGuidTransformResult(
    val componentCount: Int,
    val explicitGuidCount: Int,
    val addedGuidCount: Int,
    val legacyPackageCleanupComponentId: String,
    val removedShortcutComponentIds: Set<String>,
    val mappings: Map<String, ComponentGuidMapping>,
)

object JpackageComponentGuidNamespace {
    const val NAME_PREFIX = "jpackage-component-guid-v1:"
    const val WIX4_NAMESPACE = "http://wixtoolset.org/schemas/v4/wxs"
    const val LEGACY_PACKAGE_FILE_NAME = ".package"
    const val LEGACY_PACKAGE_CLEANUP_ID = "JpRemoveLegacyPackageMetadata"

    fun canonicalGuid(value: String): String {
        val trimmed = value.trim()
        val hasOpeningBrace = trimmed.startsWith('{')
        val hasClosingBrace = trimmed.endsWith('}')
        require(hasOpeningBrace == hasClosingBrace) { "Malformed GUID braces: $value" }
        val withoutBraces = if (hasOpeningBrace) trimmed.substring(1, trimmed.length - 1) else trimmed
        return UUID.fromString(withoutBraces).toString()
    }

    fun uuidV5(namespace: UUID, name: String): UUID {
        val namespaceBytes = ByteBuffer.allocate(16)
            .putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits)
            .array()
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(namespaceBytes)
        val bytes = digest.digest(nameBytes).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    fun namespaceComponentGuid(namespace: UUID, effectiveOriginalGuid: String): String {
        val canonicalOriginal = canonicalGuid(effectiveOriginalGuid)
        return formatMsiGuid(uuidV5(namespace, NAME_PREFIX + canonicalOriginal))
    }

    fun formatMsiGuid(uuid: UUID): String =
        "{${uuid.toString().uppercase(Locale.ROOT)}}"

    fun transform(
        sourceBundle: Path,
        probeComponents: Map<String, String>,
        outputBundle: Path,
        namespace: UUID,
        excludedShortcutLauncherNames: Set<String> = emptySet(),
    ): ComponentGuidTransformResult {
        require(Files.isRegularFile(sourceBundle)) { "Missing source bundle.wxf: $sourceBundle" }
        val document = readDocument(sourceBundle)
        validateRoot(document)
        val legacyPackageCleanupComponentId = authorLegacyPackageCleanup(document)
        val removedShortcutComponentIds = excludedShortcutLauncherNames
            .flatMapTo(linkedSetOf()) { removeGeneratedLauncherShortcuts(document, it) }
        val expectedFingerprint = semanticFingerprint(document)
        val components = componentElements(document)
        require(components.isNotEmpty()) { "bundle.wxf contains no WiX Component elements" }

        val sourceIds = components.map { componentId(it) }
        require(sourceIds.toSet().size == sourceIds.size) { "bundle.wxf contains duplicate Component Id values" }
        val expectedProbeIds = sourceIds.toSet() + removedShortcutComponentIds
        require(probeComponents.keys == expectedProbeIds) {
            val missing = expectedProbeIds - probeComponents.keys
            val unknown = probeComponents.keys - expectedProbeIds
            "bundle/probe Component mismatch: source=${sourceIds.size}, probe=${probeComponents.size}, " +
                "missing=$missing, unknown=$unknown"
        }

        val canonicalProbe = probeComponents.mapValues { (_, guid) -> formatMsiGuid(UUID.fromString(canonicalGuid(guid))) }
        require(canonicalProbe.values.toSet().size == canonicalProbe.size) {
            "Probe MSI contains a many-to-one Component GUID mapping"
        }

        var explicitCount = 0
        val addedGuidIds = mutableListOf<String>()
        val mappings = linkedMapOf<String, ComponentGuidMapping>()
        for (component in components) {
            val id = componentId(component)
            val effectiveOriginal = canonicalProbe.getValue(id)
            if (component.hasAttribute("Guid")) {
                explicitCount++
                val sourceGuid = formatMsiGuid(UUID.fromString(canonicalGuid(component.getAttribute("Guid"))))
                require(sourceGuid == effectiveOriginal) {
                    "Explicit bundle GUID does not match probe MSI for Component $id: " +
                        "bundle=$sourceGuid, probe=$effectiveOriginal"
                }
            } else {
                addedGuidIds += id
            }
            val namespaced = namespaceComponentGuid(namespace, effectiveOriginal)
            component.setAttribute("Guid", namespaced)
            mappings[id] = ComponentGuidMapping(id, effectiveOriginal, namespaced)
        }

        require(addedGuidIds.size == 1) {
            "JDK 25.0.4 contract drift: expected exactly one auto-GUID Component, found ${addedGuidIds.size}: $addedGuidIds"
        }
        val autoGuidComponent = components.single { componentId(it) == addedGuidIds.single() }
        require(addedGuidIds.single().startsWith("crm_rf") &&
            autoGuidComponent.getElementsByTagNameNS("*", "RemoveFolderEx").length == 1) {
            "JDK 25.0.4 contract drift: unrecognized auto-GUID Component ${addedGuidIds.single()}"
        }
        require(mappings.values.map { it.namespacedGuid }.toSet().size == mappings.size) {
            "Namespaced Component GUID collision detected"
        }

        Files.createDirectories(outputBundle.parent)
        val temporaryOutput = outputBundle.resolveSibling(outputBundle.fileName.toString() + ".tmp")
        writeDocument(document, temporaryOutput)
        val writtenDocument = readDocument(temporaryOutput)
        validateRoot(writtenDocument)
        val writtenFingerprint = semanticFingerprint(writtenDocument)
        require(expectedFingerprint == writtenFingerprint) {
            "bundle.wxf transform changed content outside the exact legacy .package cleanup and Component/@Guid: " +
                firstDifference(expectedFingerprint, writtenFingerprint)
        }
        val writtenComponents = componentElements(writtenDocument)
        require(writtenComponents.size == components.size && writtenComponents.all { it.hasAttribute("Guid") }) {
            "Transformed bundle.wxf does not contain explicit GUIDs for every Component"
        }
        try {
            Files.move(
                temporaryOutput,
                outputBundle,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporaryOutput, outputBundle, StandardCopyOption.REPLACE_EXISTING)
        }

        return ComponentGuidTransformResult(
            componentCount = components.size,
            explicitGuidCount = explicitCount,
            addedGuidCount = addedGuidIds.size,
            legacyPackageCleanupComponentId = legacyPackageCleanupComponentId,
            removedShortcutComponentIds = removedShortcutComponentIds,
            mappings = mappings,
        )
    }

    fun verifyFinalComponents(
        finalComponents: Map<String, String>,
        expected: ComponentGuidTransformResult,
    ) {
        require(finalComponents.keys == expected.mappings.keys) {
            val missing = expected.mappings.keys - finalComponents.keys
            val unknown = finalComponents.keys - expected.mappings.keys
            "Final MSI Component mismatch: expected=${expected.componentCount}, actual=${finalComponents.size}, " +
                "missing=$missing, unknown=$unknown"
        }
        for ((id, mapping) in expected.mappings) {
            val actual = formatMsiGuid(UUID.fromString(canonicalGuid(finalComponents.getValue(id))))
            require(actual == mapping.namespacedGuid) {
                "Final MSI GUID mismatch for Component $id: expected=${mapping.namespacedGuid}, actual=$actual"
            }
        }
    }

    fun assertOnlyExpectedChanges(
        original: Path,
        transformed: Path,
        excludedShortcutLauncherNames: Set<String> = emptySet(),
    ) {
        val originalDocument = readDocument(original)
        val transformedDocument = readDocument(transformed)
        validateRoot(originalDocument)
        validateRoot(transformedDocument)
        authorLegacyPackageCleanup(originalDocument)
        excludedShortcutLauncherNames.forEach { removeGeneratedLauncherShortcuts(originalDocument, it) }
        require(semanticFingerprint(originalDocument) == semanticFingerprint(transformedDocument)) {
            "bundle.wxf files differ outside the exact legacy .package cleanup, launcher shortcut exclusion, " +
                "and Component/@Guid"
        }
    }

    private fun removeGeneratedLauncherShortcuts(document: Document, launcherName: String): Set<String> {
        require(launcherName.isNotBlank())
        val shortcutElements = document.getElementsByTagNameNS(WIX4_NAMESPACE, "Shortcut")
        val matchingShortcuts = (0 until shortcutElements.length)
            .map { shortcutElements.item(it) as Element }
            .filter { it.getAttribute("Name") == launcherName }
        require(matchingShortcuts.size == 2) {
            "JDK 25.0.4 contract drift: expected exactly two generated shortcuts for $launcherName, " +
                "found ${matchingShortcuts.size}"
        }
        val components = matchingShortcuts.map { shortcut ->
            val component = shortcut.parentNode as? Element
                ?: error("Generated shortcut for $launcherName has no Component parent")
            require(component.localName == "Component" && component.namespaceURI == WIX4_NAMESPACE) {
                "Generated shortcut for $launcherName is not a direct Component child"
            }
            require(component.getElementsByTagNameNS(WIX4_NAMESPACE, "Shortcut").length == 1 &&
                component.getElementsByTagNameNS(WIX4_NAMESPACE, "File").length == 0) {
                "Refusing to remove a shortcut Component that owns other shortcuts or files: ${componentId(component)}"
            }
            component
        }
        val componentIds = components.mapTo(linkedSetOf(), ::componentId)
        require(componentIds.size == 2) { "Generated shortcuts for $launcherName share a Component" }
        val conditions = components.map { it.getAttribute("Condition") }.toSet()
        require(conditions == setOf("JP_INSTALL_STARTMENU_SHORTCUT", "JP_INSTALL_DESKTOP_SHORTCUT")) {
            "Generated shortcut conditions drifted for $launcherName: $conditions"
        }

        val componentGroups = document.getElementsByTagNameNS(WIX4_NAMESPACE, "ComponentGroup")
        val shortcutsGroup = (0 until componentGroups.length)
            .map { componentGroups.item(it) as Element }
            .singleOrNull { it.getAttribute("Id") == "Shortcuts" }
            ?: error("bundle.wxf has no Shortcuts ComponentGroup")
        val refs = shortcutsGroup.getElementsByTagNameNS(WIX4_NAMESPACE, "ComponentRef")
        val matchingRefs = (0 until refs.length)
            .map { refs.item(it) as Element }
            .filter { it.getAttribute("Id") in componentIds }
        require(matchingRefs.map { it.getAttribute("Id") }.toSet() == componentIds) {
            "Generated shortcut Components are not referenced exactly by the Shortcuts group: $componentIds"
        }
        matchingRefs.forEach(shortcutsGroup::removeChild)

        for (component in components) {
            val container = component.parentNode
            container.removeChild(component)
            val hasElementChildren = (0 until container.childNodes.length).any {
                container.childNodes.item(it) is Element
            }
            if (!hasElementChildren) container.parentNode.removeChild(container)
        }
        return componentIds
    }

    private fun authorLegacyPackageCleanup(document: Document): String {
        val files = document.getElementsByTagNameNS(WIX4_NAMESPACE, "File")
        val packageFiles = (0 until files.length)
            .map { files.item(it) as Element }
            .filter { file ->
                file.getAttribute("Source")
                    .replace('\\', '/')
                    .substringAfterLast('/') == LEGACY_PACKAGE_FILE_NAME
            }
        require(packageFiles.size == 1) {
            "JDK 25.0.4 contract drift: expected exactly one generated $LEGACY_PACKAGE_FILE_NAME File, " +
                "found ${packageFiles.size}"
        }

        val packageFile = packageFiles.single()
        val component = packageFile.parentNode as? Element
            ?: error("Generated $LEGACY_PACKAGE_FILE_NAME File has no Component parent")
        require(component.localName == "Component" && component.namespaceURI == WIX4_NAMESPACE) {
            "Generated $LEGACY_PACKAGE_FILE_NAME File is not a direct Component child"
        }
        val id = componentId(component)
        val directoryRef = component.parentNode as? Element
            ?: error("Generated $LEGACY_PACKAGE_FILE_NAME Component has no DirectoryRef parent")
        require(directoryRef.localName == "DirectoryRef" && directoryRef.namespaceURI == WIX4_NAMESPACE) {
            "Generated $LEGACY_PACKAGE_FILE_NAME Component is not a direct DirectoryRef child"
        }
        val directoryId = directoryRef.getAttribute("Id")
        require(directoryId.isNotBlank()) { "Generated $LEGACY_PACKAGE_FILE_NAME DirectoryRef has no Id" }
        val directories = document.getElementsByTagNameNS(WIX4_NAMESPACE, "Directory")
        val matchingDirectories = (0 until directories.length)
            .map { directories.item(it) as Element }
            .filter { it.getAttribute("Id") == directoryId }
        require(matchingDirectories.size == 1 && matchingDirectories.single().getAttribute("Name") == "app") {
            "Generated $LEGACY_PACKAGE_FILE_NAME Component directory is not the exact app directory: $directoryId"
        }

        val filesGroups = document.getElementsByTagNameNS(WIX4_NAMESPACE, "ComponentGroup")
        val filesGroup = (0 until filesGroups.length)
            .map { filesGroups.item(it) as Element }
            .singleOrNull { it.getAttribute("Id") == "Files" }
            ?: error("bundle.wxf has no Files ComponentGroup")
        val componentRefs = filesGroup.getElementsByTagNameNS(WIX4_NAMESPACE, "ComponentRef")
        require((0 until componentRefs.length).any {
            (componentRefs.item(it) as Element).getAttribute("Id") == id
        }) {
            "Generated $LEGACY_PACKAGE_FILE_NAME Component is not always active in the Files ComponentGroup: $id"
        }
        require((0 until component.childNodes.length).none {
            val child = component.childNodes.item(it)
            child is Element && child.namespaceURI == WIX4_NAMESPACE &&
                child.localName == "RemoveFile" && child.getAttribute("Id") == LEGACY_PACKAGE_CLEANUP_ID
        }) {
            "Duplicate $LEGACY_PACKAGE_CLEANUP_ID authoring"
        }

        component.removeChild(packageFile)
        val cleanup = document.createElementNS(WIX4_NAMESPACE, "RemoveFile")
        cleanup.setAttribute("Id", LEGACY_PACKAGE_CLEANUP_ID)
        cleanup.setAttribute("Name", LEGACY_PACKAGE_FILE_NAME)
        cleanup.setAttribute("On", "install")
        component.appendChild(cleanup)
        return id
    }

    private fun validateRoot(document: Document) {
        val root = document.documentElement
        require(root.localName == "Wix" && root.namespaceURI == WIX4_NAMESPACE) {
            "Unsupported bundle.wxf schema: root={${root.namespaceURI}}${root.localName}"
        }
    }

    private fun componentElements(document: Document): List<Element> {
        val nodes = document.getElementsByTagNameNS(WIX4_NAMESPACE, "Component")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun componentId(component: Element): String {
        val id = component.getAttribute("Id")
        require(id.isNotBlank()) { "WiX Component is missing Id" }
        return id
    }

    private fun readDocument(path: Path): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
    }

    private fun writeDocument(document: Document, path: Path) {
        val factory = TransformerFactory.newInstance()
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }
        Files.newOutputStream(path).use { transformer.transform(DOMSource(document), StreamResult(it)) }
    }

    private fun semanticFingerprint(document: Document): String = buildString {
        appendNode(document.documentElement, this)
    }

    private fun firstDifference(expected: String, actual: String): String {
        val index = (0 until minOf(expected.length, actual.length)).firstOrNull { expected[it] != actual[it] }
            ?: minOf(expected.length, actual.length)
        val start = maxOf(0, index - 80)
        val expectedEnd = minOf(expected.length, index + 160)
        val actualEnd = minOf(actual.length, index + 160)
        return "index=$index, expected='${expected.substring(start, expectedEnd)}', " +
            "actual='${actual.substring(start, actualEnd)}'"
    }

    private fun appendNode(node: Node, output: StringBuilder) {
        when (node.nodeType) {
            Node.ELEMENT_NODE -> {
                val element = node as Element
                output.append('<').append(element.namespaceURI).append('|').append(element.localName)
                val attributes = (0 until element.attributes.length)
                    .map { element.attributes.item(it) }
                    .filterNot {
                        it.namespaceURI == XMLConstants.XMLNS_ATTRIBUTE_NS_URI ||
                            (
                                element.localName == "Component" &&
                                    element.namespaceURI == WIX4_NAMESPACE &&
                                    (it.localName ?: it.nodeName) == "Guid"
                            )
                    }
                    .sortedBy { "${it.namespaceURI}|${it.localName ?: it.nodeName}" }
                for (attribute in attributes) {
                    output.append('@')
                        .append(attribute.namespaceURI)
                        .append('|')
                        .append(attribute.localName ?: attribute.nodeName)
                        .append('=')
                        .append(attribute.nodeValue)
                }
                output.append('>')
                for (index in 0 until element.childNodes.length) {
                    appendNode(element.childNodes.item(index), output)
                }
                output.append("</").append(element.localName).append('>')
            }
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                val text = node.nodeValue
                if (text.isNotBlank()) output.append("#text[").append(text).append(']')
            }
            Node.COMMENT_NODE -> output.append("#comment[").append(node.nodeValue).append(']')
        }
    }
}
