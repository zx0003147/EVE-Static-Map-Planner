package dev.evestaticmapplanner.packaging

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.concurrent.thread

data class MsiLegacyPackageCleanupRow(
    val fileKey: String,
    val componentId: String,
    val fileName: String,
    val directoryId: String,
    val directoryName: String,
    val installMode: Int,
)

data class MsiLegacyPackageCleanupState(
    val packageFileCount: Int,
    val cleanupRows: List<MsiLegacyPackageCleanupRow>,
)

object MsiLegacyPackageCleanupReader {
    fun read(msi: Path): MsiLegacyPackageCleanupState {
        require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "MSI legacy package cleanup inspection is supported only on Windows"
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
        val encodedQuery = Base64.getEncoder().encodeToString(POWERSHELL_QUERY.toByteArray(Charsets.UTF_16LE))
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
            environment()["JPACKAGE_CLEANUP_MSI"] = msi.toAbsolutePath().normalize().toString()
        }.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = thread(name = "msi-cleanup-stdout") {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stdout.append(it.readText()) }
        }
        val stderrThread = thread(name = "msi-cleanup-stderr") {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { stderr.append(it.readText()) }
        }
        val exitCode = process.waitFor()
        stdoutThread.join()
        stderrThread.join()
        require(exitCode == 0) {
            "Could not read MSI cleanup authoring (exit $exitCode): ${stderr.toString().trim()}"
        }
        require(stderr.isBlank()) { "MSI cleanup reader wrote stderr: ${stderr.toString().trim()}" }
        return parseRows(stdout.toString())
    }

    fun verify(msi: Path, expectedComponentId: String): MsiLegacyPackageCleanupRow {
        val state = read(msi)
        require(state.packageFileCount == 0) {
            "Final MSI still contains ${JpackageComponentGuidNamespace.LEGACY_PACKAGE_FILE_NAME} in the File table"
        }
        require(state.cleanupRows.size == 1) {
            "Final MSI must contain exactly one legacy .package RemoveFile row; found ${state.cleanupRows.size}"
        }
        val cleanup = state.cleanupRows.single()
        val longFileName = cleanup.fileName.substringAfter('|', cleanup.fileName)
        require(longFileName == JpackageComponentGuidNamespace.LEGACY_PACKAGE_FILE_NAME) {
            "Legacy cleanup is not exact: ${cleanup.fileName}"
        }
        require('*' !in cleanup.fileName && '?' !in cleanup.fileName) {
            "Legacy cleanup must not contain a wildcard: ${cleanup.fileName}"
        }
        require(cleanup.installMode == 1) {
            "Legacy cleanup must run on install (InstallMode=1), found ${cleanup.installMode}"
        }
        require(cleanup.componentId == expectedComponentId) {
            "Legacy cleanup Component drift: expected=$expectedComponentId, actual=${cleanup.componentId}"
        }
        val longDirectoryName = cleanup.directoryName.substringAfter('|', cleanup.directoryName)
        require(longDirectoryName == "app") {
            "Legacy cleanup directory is not the exact app directory: ${cleanup.directoryName}"
        }
        return cleanup
    }

    fun parseRows(output: String): MsiLegacyPackageCleanupState {
        var packageFileCount: Int? = null
        val cleanupRows = mutableListOf<MsiLegacyPackageCleanupRow>()
        for ((lineNumber, rawLine) in output.lineSequence().withIndex()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "FILE_COUNT" -> {
                    require(fields.size == 2 && packageFileCount == null) {
                        "Malformed duplicate MSI File count at line ${lineNumber + 1}: $rawLine"
                    }
                    packageFileCount = fields[1].toInt()
                }
                "REMOVE_FILE" -> {
                    require(fields.size == 7) { "Malformed MSI RemoveFile row at line ${lineNumber + 1}: $rawLine" }
                    cleanupRows += MsiLegacyPackageCleanupRow(
                        fileKey = fields[1],
                        componentId = fields[2],
                        fileName = fields[3],
                        directoryId = fields[4],
                        directoryName = fields[5],
                        installMode = fields[6].toInt(),
                    )
                }
                else -> error("Unknown MSI cleanup row at line ${lineNumber + 1}: $rawLine")
            }
        }
        return MsiLegacyPackageCleanupState(
            packageFileCount = requireNotNull(packageFileCount) { "MSI cleanup output has no File count" },
            cleanupRows = cleanupRows,
        )
    }

    private val POWERSHELL_QUERY = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}ProgressPreference = 'SilentlyContinue'
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        ${'$'}installer = New-Object -ComObject WindowsInstaller.Installer
        try {
            ${'$'}database = ${'$'}installer.OpenDatabase(${'$'}env:JPACKAGE_CLEANUP_MSI, 0)
            ${'$'}fileView = ${'$'}database.OpenView('SELECT `FileName` FROM `File`')
            ${'$'}fileView.Execute()
            ${'$'}packageFileCount = 0
            while (${'$'}null -ne (${'$'}record = ${'$'}fileView.Fetch())) {
                ${'$'}fileName = ${'$'}record.StringData(1)
                if (${'$'}fileName.Substring(${'$'}fileName.LastIndexOf('|') + 1) -eq '.package') {
                    ${'$'}packageFileCount++
                }
            }
            ${'$'}fileView.Close()
            [Console]::Out.WriteLine("FILE_COUNT`t${'$'}packageFileCount")

            ${'$'}removeView = ${'$'}database.OpenView('SELECT `FileKey`,`Component_`,`FileName`,`DirProperty`,`InstallMode` FROM `RemoveFile`')
            ${'$'}removeView.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}removeView.Fetch())) {
                ${'$'}removeFileName = ${'$'}record.StringData(3)
                if (${'$'}removeFileName.Substring(${'$'}removeFileName.LastIndexOf('|') + 1) -ne '.package') { continue }
                ${'$'}componentId = ${'$'}record.StringData(2)
                ${'$'}componentView = ${'$'}database.OpenView("SELECT ``Directory_`` FROM ``Component`` WHERE ``Component``='${'$'}componentId'")
                ${'$'}componentView.Execute()
                ${'$'}componentRecord = ${'$'}componentView.Fetch()
                if (${'$'}null -eq ${'$'}componentRecord) { throw "RemoveFile Component missing: ${'$'}componentId" }
                ${'$'}directoryId = ${'$'}componentRecord.StringData(1)
                ${'$'}componentView.Close()
                ${'$'}directoryView = ${'$'}database.OpenView("SELECT ``DefaultDir`` FROM ``Directory`` WHERE ``Directory``='${'$'}directoryId'")
                ${'$'}directoryView.Execute()
                ${'$'}directoryRecord = ${'$'}directoryView.Fetch()
                if (${'$'}null -eq ${'$'}directoryRecord) { throw "RemoveFile Directory missing: ${'$'}directoryId" }
                ${'$'}directoryName = ${'$'}directoryRecord.StringData(1)
                ${'$'}directoryView.Close()
                [Console]::Out.WriteLine("REMOVE_FILE`t${'$'}(${'$'}record.StringData(1))`t${'$'}componentId`t${'$'}(${'$'}record.StringData(3))`t${'$'}directoryId`t${'$'}directoryName`t${'$'}(${'$'}record.IntegerData(5))")
            }
            ${'$'}removeView.Close()
        } finally {
            if (${'$'}null -ne ${'$'}installer) {
                [System.Runtime.InteropServices.Marshal]::ReleaseComObject(${'$'}installer) | Out-Null
            }
        }
    """.trimIndent()
}
