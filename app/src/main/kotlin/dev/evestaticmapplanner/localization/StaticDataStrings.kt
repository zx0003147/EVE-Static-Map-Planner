package dev.evestaticmapplanner.localization

import dev.evestaticmapplanner.sde.update.SdeUpdateComparison
import dev.evestaticmapplanner.sde.update.SdeUpdaterPhase

interface StaticDataStrings {
    val setupTitle: String
    val noStaticDataInstalled: String
    val title: String
    val mode: String
    val managedDatabase: String
    val externalDatabase: String
    val database: String
    val currentBuild: String
    val latestBuild: String
    val lastChecked: String
    val status: String
    val notInstalled: String
    val notChecked: String
    val never: String
    val externalDatabaseWarning: String
    val unknownSize: String
    val checkForUpdates: String
    val installStaticData: String
    val downloadAndPrepare: String
    val cancel: String
    val discardPendingUpdate: String

    fun comparison(value: SdeUpdateComparison?): String
    fun phase(value: SdeUpdaterPhase, pendingBuild: Long?): String
    fun updateFailed(technicalDetail: String?): String
}
