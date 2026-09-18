package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.localization.StaticDataStrings
import dev.evestaticmapplanner.sde.update.SdeUpdateComparison
import dev.evestaticmapplanner.sde.update.SdeUpdaterPhase

internal object SimplifiedChineseStaticDataStrings : StaticDataStrings {
    override val setupTitle = "静态数据设置"
    override val noStaticDataInstalled = "尚未安装静态数据"
    override val title = "静态数据"
    override val mode = "模式"
    override val managedDatabase = "托管数据库"
    override val externalDatabase = "外部数据库"
    override val database = "数据库"
    override val currentBuild = "当前版本"
    override val latestBuild = "可用版本"
    override val lastChecked = "上次检查"
    override val status = "状态"
    override val notInstalled = "未安装"
    override val notChecked = "未检查"
    override val never = "从未"
    override val externalDatabaseWarning = "无法自动替换此外部数据库文件。"
    override val unknownSize = "未知"
    override val checkForUpdates = "检查更新"
    override val installStaticData = "安装静态数据"
    override val downloadAndPrepare = "下载并准备"
    override val cancel = "取消"
    override val discardPendingUpdate = "丢弃待启用更新"

    override fun comparison(value: SdeUpdateComparison?): String = when (value) {
        SdeUpdateComparison.INSTALL_AVAILABLE -> "可安装"
        SdeUpdateComparison.UPDATE_AVAILABLE -> "有可用更新"
        SdeUpdateComparison.UP_TO_DATE -> "已是最新"
        SdeUpdateComparison.LOCAL_NEWER -> "本地版本较新"
        null -> "空闲"
    }

    override fun phase(value: SdeUpdaterPhase, pendingBuild: Long?): String = when (value) {
        SdeUpdaterPhase.IDLE -> comparison(null)
        SdeUpdaterPhase.CHECKING -> "正在检查更新"
        SdeUpdaterPhase.DOWNLOADING -> "正在下载"
        SdeUpdaterPhase.EXTRACTING -> "正在解压"
        SdeUpdaterPhase.READING_REGIONS -> "正在导入星域"
        SdeUpdaterPhase.READING_CONSTELLATIONS -> "正在导入星座"
        SdeUpdaterPhase.READING_SYSTEMS -> "正在导入星系"
        SdeUpdaterPhase.READING_STARGATES -> "正在导入星门"
        SdeUpdaterPhase.VALIDATING_REFERENCES -> "正在验证引用"
        SdeUpdaterPhase.BUILDING_DATABASE -> "正在构建数据库"
        SdeUpdaterPhase.VALIDATING_DATABASE -> "正在验证数据库"
        SdeUpdaterPhase.RESTART_REQUIRED -> "需要重启以启用版本 $pendingBuild"
        SdeUpdaterPhase.APPLYING -> "正在启用静态数据"
        SdeUpdaterPhase.SUCCEEDED -> "静态数据已安装"
        SdeUpdaterPhase.FAILED -> "更新失败"
        SdeUpdaterPhase.FATAL -> "静态数据不可用"
    }

    override fun updateFailed(technicalDetail: String?): String =
        technicalDetail?.takeIf(String::isNotBlank)?.let { "静态数据更新失败。\n$it" }
            ?: "静态数据更新失败。"
}
