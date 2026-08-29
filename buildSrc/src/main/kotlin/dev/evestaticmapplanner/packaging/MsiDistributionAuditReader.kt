package dev.evestaticmapplanner.packaging

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.concurrent.thread

data class MsiDistributionAudit(
    val tables: Set<String>,
    val properties: Map<String, String>,
    val summaryTemplate: String,
    val files: List<List<String>>,
    val components: List<List<String>>,
    val features: List<List<String>>,
    val shortcuts: List<List<String>>,
    val removeFiles: List<List<String>>,
    val upgrades: List<List<String>>,
    val installExecuteSequence: List<List<String>>,
    val customActions: List<List<String>>,
    val removeFolderEx: List<List<String>>,
    val directories: List<List<String>>,
    val environment: List<List<String>>,
) {
    val productCode: String get() = properties.getValue("ProductCode")
    val productName: String get() = properties.getValue("ProductName")
    val productVersion: String get() = properties.getValue("ProductVersion")
    val upgradeCode: String get() = properties.getValue("UpgradeCode")
    val manufacturer: String get() = properties.getValue("Manufacturer")
}

object MsiDistributionAuditReader {
    fun read(msi: Path): MsiDistributionAudit {
        require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "MSI distribution inspection is supported only on Windows"
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
            environment()["JPACKAGE_AUDIT_MSI"] = msi.toAbsolutePath().normalize().toString()
        }.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = thread(name = "msi-audit-stdout") {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { stdout.append(it.readText()) }
        }
        val stderrThread = thread(name = "msi-audit-stderr") {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { stderr.append(it.readText()) }
        }
        val exitCode = process.waitFor()
        stdoutThread.join()
        stderrThread.join()
        require(exitCode == 0) { "Could not audit MSI tables (exit $exitCode): ${stderr.toString().trim()}" }
        require(stderr.isBlank()) { "MSI audit reader wrote stderr: ${stderr.toString().trim()}" }
        return parseRows(stdout.toString())
    }

    fun parseRows(output: String): MsiDistributionAudit {
        val rows = linkedMapOf<String, MutableList<List<String>>>()
        for ((lineNumber, rawLine) in output.lineSequence().withIndex()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) continue
            val fields = line.split('\t', ignoreCase = false, limit = 20)
            require(fields.size >= 2 && fields[0].isNotBlank()) {
                "Malformed MSI audit row at line ${lineNumber + 1}: $rawLine"
            }
            rows.getOrPut(fields[0]) { mutableListOf() } += fields.drop(1)
        }
        val properties = rows["PROPERTY"].orEmpty().associate { fields ->
            require(fields.size == 2 && fields[0].isNotBlank()) { "Malformed MSI PROPERTY row: $fields" }
            fields[0] to fields[1]
        }
        fun records(kind: String, size: Int): List<List<String>> = rows[kind].orEmpty().onEach {
            require(it.size == size) { "Malformed MSI $kind row: $it" }
        }
        return MsiDistributionAudit(
            tables = records("TABLE", 1).mapTo(sortedSetOf()) { it.single() },
            properties = properties,
            summaryTemplate = records("SUMMARY_TEMPLATE", 1).singleOrNull()?.single()
                ?: error("MSI audit has no summary template"),
            files = records("FILE", 4),
            components = records("COMPONENT", 3),
            features = records("FEATURE", 3),
            shortcuts = records("SHORTCUT", 5),
            removeFiles = records("REMOVE_FILE", 5),
            upgrades = records("UPGRADE", 5),
            installExecuteSequence = records("INSTALL_SEQUENCE", 3),
            customActions = records("CUSTOM_ACTION", 4),
            removeFolderEx = records("REMOVE_FOLDER_EX", 5),
            directories = records("DIRECTORY", 3),
            environment = records("ENVIRONMENT", 4),
        )
    }

    private val POWERSHELL_QUERY = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}ProgressPreference = 'SilentlyContinue'
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        ${'$'}installer = New-Object -ComObject WindowsInstaller.Installer
        try {
            ${'$'}database = ${'$'}installer.OpenDatabase(${'$'}env:JPACKAGE_AUDIT_MSI, 0)
            ${'$'}summary = ${'$'}installer.SummaryInformation(${'$'}env:JPACKAGE_AUDIT_MSI, 0)
            [Console]::Out.WriteLine("SUMMARY_TEMPLATE`t${'$'}(${'$'}summary.Property(7))")

            ${'$'}view = ${'$'}database.OpenView('SELECT `Name` FROM `_Tables`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("TABLE`t${'$'}(${'$'}record.StringData(1))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Property`,`Value` FROM `Property`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("PROPERTY`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `File`,`Component_`,`FileName`,`FileSize` FROM `File`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("FILE`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))`t${'$'}(${'$'}record.IntegerData(4))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Component`,`ComponentId`,`Directory_` FROM `Component`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("COMPONENT`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Feature`,`Feature_Parent`,`Title` FROM `Feature`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("FEATURE`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Shortcut`,`Directory_`,`Name`,`Component_`,`Target` FROM `Shortcut`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("SHORTCUT`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))`t${'$'}(${'$'}record.StringData(4))`t${'$'}(${'$'}record.StringData(5))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `FileKey`,`Component_`,`FileName`,`DirProperty`,`InstallMode` FROM `RemoveFile`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("REMOVE_FILE`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))`t${'$'}(${'$'}record.StringData(4))`t${'$'}(${'$'}record.IntegerData(5))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `UpgradeCode`,`VersionMin`,`VersionMax`,`Attributes`,`ActionProperty` FROM `Upgrade`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("UPGRADE`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))`t${'$'}(${'$'}record.IntegerData(4))`t${'$'}(${'$'}record.StringData(5))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Action`,`Condition`,`Sequence` FROM `InstallExecuteSequence`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("INSTALL_SEQUENCE`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.IntegerData(3))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Action`,`Type`,`Source`,`Target` FROM `CustomAction`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("CUSTOM_ACTION`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.IntegerData(2))`t${'$'}(${'$'}record.StringData(3))`t${'$'}(${'$'}record.StringData(4))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `RemoveFolderEx`,`Component_`,`Property`,`InstallMode`,`Condition` FROM `Wix4RemoveFolderEx`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("REMOVE_FOLDER_EX`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))`t${'$'}(${'$'}record.IntegerData(4))`t${'$'}(${'$'}record.StringData(5))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Directory`,`Directory_Parent`,`DefaultDir` FROM `Directory`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("DIRECTORY`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))")
            }
            ${'$'}view.Close()

            ${'$'}view = ${'$'}database.OpenView('SELECT `Environment`,`Name`,`Value`,`Component_` FROM `Environment`')
            ${'$'}view.Execute()
            while (${'$'}null -ne (${'$'}record = ${'$'}view.Fetch())) {
                [Console]::Out.WriteLine("ENVIRONMENT`t${'$'}(${'$'}record.StringData(1))`t${'$'}(${'$'}record.StringData(2))`t${'$'}(${'$'}record.StringData(3))`t${'$'}(${'$'}record.StringData(4))")
            }
            ${'$'}view.Close()
        } finally {
            if (${'$'}null -ne ${'$'}installer) {
                [System.Runtime.InteropServices.Marshal]::ReleaseComObject(${'$'}installer) | Out-Null
            }
        }
    """.trimIndent()
}
