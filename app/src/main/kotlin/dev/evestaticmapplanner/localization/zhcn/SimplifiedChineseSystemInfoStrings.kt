package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.localization.SystemInfoStrings
import dev.evestaticmapplanner.core.sovereignty.SovereigntyFreshness
import dev.evestaticmapplanner.core.sovereignty.SovereigntyStatus
import dev.evestaticmapplanner.core.sovereignty.SystemOwnerKind

internal object SimplifiedChineseSystemInfoStrings : SystemInfoStrings {
    override val selectedSystem = "已选星系"
    override val noSystemSelected = "未选择星系"
    override val loadingSystemDetails = "正在加载星系详情…"
    override val systemId = "星系 ID"
    override val region = "星域"
    override val constellation = "星座"
    override val securityStatus = "安全等级"
    override val stargates = "星门"
    override val ansiblex = "Ansiblex"
    override val jumpCoverage = "跳跃覆盖"
    override val ansiblexConnections = "Ansiblex 连接"
    override val jumpOverlays = "跳跃范围覆盖"
    override val inSelectedOverlayIntersection = "位于已选覆盖交集中"
    override val marker = "标记"
    override val sharedMarker = "共享标记"
    override val color = "颜色"
    override val tags = "标签"
    override val notes = "备注"
    override val sharedMapDataMayBeStale = "共享地图数据可能已过期"
    override val editSharedMarker = "编辑共享标记"
    override val saved = "已保存"
    override val temporary = "临时"
    override val bidirectional = "双向"
    override val outbound = "出站"
    override val inbound = "入站"
    override val available = "可用"
    override val unavailable = "不可用"
    override val ownerUnknown = "Owner 未知"
    override val sovereignty = "主权"
    override val sovereigntyOwnerKind = "所有者类型"
    override val sovereigntyAlliance = "联盟"
    override val sovereigntyAllianceId = "联盟 ID"
    override val sovereigntyCorporation = "军团"
    override val sovereigntyCorporationId = "军团 ID"
    override val sovereigntyStatus = "主权状态"
    override val sovereigntyFreshness = "数据新鲜度"
    override fun regionValue(value: String) = "星域：$value"
    override fun constellationValue(value: String) = "星座：$value"
    override fun updatedBy(displayName: String) = "更新者：$displayName"
    override fun fallbackSystem(systemId: Int) = "星系 $systemId"
    override fun sovereigntyOwnerKind(kind: SystemOwnerKind) = when (kind) {
        SystemOwnerKind.ALLIANCE -> "联盟"
        SystemOwnerKind.CORPORATION -> "军团"
        SystemOwnerKind.FACTION -> "势力"
        SystemOwnerKind.UNCLAIMED -> "未占领"
        SystemOwnerKind.UNKNOWN -> "未知"
    }
    override fun sovereigntyStatus(status: SovereigntyStatus) = when (status) {
        SovereigntyStatus.CLAIMED -> "已占领"
        SovereigntyStatus.UNCLAIMED -> "未占领"
        SovereigntyStatus.UNKNOWN -> "未知"
    }
    override fun sovereigntyFreshness(freshness: SovereigntyFreshness) = when (freshness) {
        SovereigntyFreshness.AVAILABLE -> "可用"
        SovereigntyFreshness.STALE -> "已过期"
        SovereigntyFreshness.UNAVAILABLE -> "不可用"
    }
}
