package dev.evestaticmapplanner.packaging

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.concurrent.thread

object MsiComponentTableReader {
    fun read(msi: Path): Map<String, String> {
        require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "MSI Component table inspection is supported only on Windows"
        }
        require(Files.isRegularFile(msi)) { "Missing MSI: $msi" }
        val powershell = Path.of(
            System.getenv("SystemRoot") ?: "C:\\Windows",
            "System32",
            "WindowsPowerShell",
            "v1.0",
            "powershell.exe",
        )
        require(Files.isRegularFile(powershell)) { "Windows PowerShell is unavailable: $powershell" }
        val encodedQuery = Base64.getEncoder().encodeToString(
            POWERSHELL_QUERY.toByteArray(Charsets.UTF_16LE),
        )
        val process = ProcessBuilder(
            powershell.toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-EncodedCommand",
            encodedQuery,
        ).apply {
            environment()["JPACKAGE_COMPONENT_MSI"] = msi.toAbsolutePath().normalize().toString()
        }.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = thread(name = "msi-component-stdout") {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stdout.append(it.readText()) }
        }
        val stderrThread = thread(name = "msi-component-stderr") {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { stderr.append(it.readText()) }
        }
        val exitCode = process.waitFor()
        stdoutThread.join()
        stderrThread.join()
        require(exitCode == 0) {
            "Could not read MSI Component table (exit $exitCode): ${stderr.toString().trim()}"
        }
        require(stderr.isBlank()) { "MSI Component table reader wrote stderr: ${stderr.toString().trim()}" }
        return parseRows(stdout.toString())
    }

    fun parseRows(output: String): Map<String, String> {
        val components = linkedMapOf<String, String>()
        for ((lineNumber, rawLine) in output.lineSequence().withIndex()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val fields = line.split('\t')
            require(fields.size == 2 && fields.all { it.isNotBlank() }) {
                "Malformed MSI Component row at line ${lineNumber + 1}: $rawLine"
            }
            val id = fields[0]
            val guid = JpackageComponentGuidNamespace.formatMsiGuid(
                java.util.UUID.fromString(JpackageComponentGuidNamespace.canonicalGuid(fields[1])),
            )
            require(components.put(id, guid) == null) { "Duplicate MSI Component row: $id" }
        }
        require(components.isNotEmpty()) { "MSI Component table is empty" }
        val duplicateGuids = components.entries.groupBy { it.value }.filterValues { it.size > 1 }
        require(duplicateGuids.isEmpty()) { "MSI Component table has many-to-one GUIDs: $duplicateGuids" }
        return components.toSortedMap()
    }

    private val POWERSHELL_QUERY = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}ProgressPreference = 'SilentlyContinue'
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        ${'$'}installer = New-Object -ComObject WindowsInstaller.Installer
        try {
            ${'$'}database = ${'$'}installer.OpenDatabase(${'$'}env:JPACKAGE_COMPONENT_MSI, 0)
            ${'$'}view = ${'$'}database.OpenView('SELECT `Component`,`ComponentId` FROM `Component`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                ${'$'}component = ${'$'}record.StringData(1)
                ${'$'}guid = ${'$'}record.StringData(2)
                if ([string]::IsNullOrWhiteSpace(${'$'}component) -or [string]::IsNullOrWhiteSpace(${'$'}guid)) {
                    throw 'Component table contains an empty Component or ComponentId value.'
                }
                [Console]::Out.WriteLine("${'$'}component`t${'$'}guid")
            }
            ${'$'}view.Close()
        } finally {
            if (${'$'}null -ne ${'$'}installer) {
                [System.Runtime.InteropServices.Marshal]::ReleaseComObject(${'$'}installer) | Out-Null
            }
        }
    """.trimIndent()
}
