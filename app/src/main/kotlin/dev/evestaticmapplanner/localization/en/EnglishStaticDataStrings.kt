package dev.evestaticmapplanner.localization.en

import dev.evestaticmapplanner.localization.StaticDataStrings
import dev.evestaticmapplanner.localization.StaticDatabaseStartupIssue
import dev.evestaticmapplanner.sde.update.SdeUpdateComparison
import dev.evestaticmapplanner.sde.update.SdeUpdaterPhase

internal object EnglishStaticDataStrings : StaticDataStrings {
    override val setupTitle = "Static Data Setup"
    override val noStaticDataInstalled = "No static data installed"
    override val title = "Static Data"
    override val mode = "Mode"
    override val managedDatabase = "Managed database"
    override val externalDatabase = "External database"
    override val database = "Database"
    override val currentBuild = "Current Build"
    override val latestBuild = "Available Build"
    override val lastChecked = "Last checked"
    override val status = "Status"
    override val notInstalled = "Not installed"
    override val notChecked = "Not checked"
    override val never = "Never"
    override val externalDatabaseWarning = "Updates cannot replace this file automatically."
    override val unknownSize = "unknown"
    override val checkForUpdates = "Check for Updates"
    override val installStaticData = "Install Static Data"
    override val downloadAndPrepare = "Download & Prepare"
    override val cancel = "Cancel"
    override val discardPendingUpdate = "Discard Pending Update"
    override val externalDatabaseErrorTitle = "External static database error"
    override val fatalStaticDataErrorTitle = "Fatal static-data error"

    override fun comparison(value: SdeUpdateComparison?): String = when (value) {
        SdeUpdateComparison.INSTALL_AVAILABLE -> "Install available"
        SdeUpdateComparison.UPDATE_AVAILABLE -> "Update available"
        SdeUpdateComparison.UP_TO_DATE -> "Up to date"
        SdeUpdateComparison.LOCAL_NEWER -> "Local build is newer"
        null -> "Idle"
    }

    override fun phase(value: SdeUpdaterPhase, pendingBuild: Long?): String = when (value) {
        SdeUpdaterPhase.IDLE -> comparison(null)
        SdeUpdaterPhase.CHECKING -> "Checking for updates"
        SdeUpdaterPhase.DOWNLOADING -> "Downloading"
        SdeUpdaterPhase.EXTRACTING -> "Extracting"
        SdeUpdaterPhase.READING_REGIONS -> "Importing regions"
        SdeUpdaterPhase.READING_CONSTELLATIONS -> "Importing constellations"
        SdeUpdaterPhase.READING_SYSTEMS -> "Importing systems"
        SdeUpdaterPhase.READING_STARGATES -> "Importing stargates"
        SdeUpdaterPhase.VALIDATING_REFERENCES -> "Validating references"
        SdeUpdaterPhase.BUILDING_DATABASE -> "Building database"
        SdeUpdaterPhase.VALIDATING_DATABASE -> "Validating database"
        SdeUpdaterPhase.RESTART_REQUIRED -> "Restart required to install build $pendingBuild"
        SdeUpdaterPhase.APPLYING -> "Activating static data"
        SdeUpdaterPhase.SUCCEEDED -> "Static data installed"
        SdeUpdaterPhase.FAILED -> "Update failed"
        SdeUpdaterPhase.FATAL -> "Static data is unavailable"
    }

    override fun updateFailed(technicalDetail: String?): String =
        technicalDetail?.takeIf(String::isNotBlank)?.let { "Static data update failed.\n$it" }
            ?: "Static data update failed."

    override fun startupError(
        issue: StaticDatabaseStartupIssue,
        expectedSchema: Int,
        actualSchema: Int?,
    ): String = when (issue) {
        StaticDatabaseStartupIssue.EXTERNAL_DATABASE_MISSING ->
            "The external static database does not exist or is not a regular file."
        StaticDatabaseStartupIssue.DATABASE_SCHEMA_OLDER ->
            "Database version is incompatible. This application requires static DB schema v$expectedSchema" +
                actualSchema?.let { ", but the database uses v$it" }.orEmpty() +
                ". Rebuild the database with the current application."
        StaticDatabaseStartupIssue.DATABASE_SCHEMA_NEWER ->
            "This database was created by a newer application/schema and cannot be opened safely."
        StaticDatabaseStartupIssue.DATABASE_INVALID ->
            "The static database is invalid or incompatible."
        StaticDatabaseStartupIssue.MANAGED_SCHEMA_UPGRADE_FAILED ->
            "The managed static database requires an upgrade, but the rebuild failed. " +
                "The previous database was preserved; check the SDE cache or network and restart to retry."
        StaticDatabaseStartupIssue.MANAGED_PATH_INVALID ->
            "The managed static database location is invalid."
    }
}
