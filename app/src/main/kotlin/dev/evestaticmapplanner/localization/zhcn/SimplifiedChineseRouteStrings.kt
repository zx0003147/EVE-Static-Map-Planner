package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.localization.NavigationStopUiRole
import dev.evestaticmapplanner.localization.RouteStrings
import java.util.Locale

internal object SimplifiedChineseRouteStrings : RouteStrings {
    override val jumpRangeOverlays = "跳跃范围覆盖"
    override val normalRoute = "普通路线"
    override val capitalRoute = "旗舰路线"
    override val overlayOrigin = "覆盖起点"
    override val effectiveMaximumLy = "有效最大跳跃距离（LY）"
    override val intersect = "交集"
    override val start = "起点"
    override val destinationOptional = "终点（可选）"
    override val capitalStart = "旗舰起点"
    override val capitalDestinationOptional = "旗舰终点（可选）"
    override val calculate = "计算路线"
    override val calculating = "正在计算…"
    override val needsRecalculation = "需要重新计算"
    override val useAnsiblex = "使用 Ansiblex"
    override val useWormholes = "使用虫洞"
    override val showAnsiblexLayer = "显示 Ansiblex 图层"
    override val waypoints = "航点"
    override val waypointHint = "航点 · 可在星系右键菜单中添加"
    override val routeActionsUnavailableForWormholes = "含虫洞的路线无法使用路线操作。"
    override val stargateOnlyRoutingAvailable = "静态地图和仅星门路线仍可使用。"
    override val validatesStaticCapitalRules = "仅验证真实 XYZ 几何、手动最大范围和已实现的静态可跳规则。"
    override val capitalLiveStateDisclaimer =
        "不验证实时诱导类型、反跳跃、ACL、燃料、电容、疲劳、反跃或服务器状态。"
    override val phaseLabel = "阶段 5 · 跳跃范围覆盖 + 旗舰路线 V1"
    override val sameNormalSystem = "起点和终点为同一星系 · 0 跳"
    override val normalRouteUnreachable = "使用当前连接类型无法到达。"
    override val invalidNormalEndpoints = "路线的一个或两个端点无效。"
    override val sameCapitalSystem = "起点和终点相同 · 0 跳"
    override val capitalRouteUnreachable = "手动跳跃范围内没有符合静态规则的路线。"
    override val invalidCapitalEndpoints = "旗舰路线的一个或两个端点无效。"
    override val draftOnly = "仅为草稿——只有点击下方按钮才会更改 EVE。"
    override val succeeded = "已成功"
    override val rejected = "已拒绝"
    override val failed = "已失败"
    override val unavailableSuffix = "不可用"
    override val disconnectedUnavailable = "已断开 / 不可用"
    override val selectTarget = "选择…"
    override val publishNormalRoute = "将普通路线发布到 Web"
    override val publishCapitalRoute = "将旗舰路线发布到 Web"
    override val calculateBeforePublishing = "请先计算路线再发布。"
    override val connectSharedMapBeforePublishing = "请先连接共享地图再发布。"
    override val routeHandoffsUnsupported = "此共享地图服务器不支持路线交接。"
    override val publishPermissionRequired = "发布需要 EDITOR 或 ADMIN 权限。"
    override val publishingRoute = "正在发布路线…"
    override val unableToLoadRouteGraph = "无法加载路线图"
    override val unableToLoadCapitalRouteData = "无法加载旗舰路线数据"
    override val unableToLoadJumpOverlayData = "无法加载跳跃范围覆盖数据"
    override val jumpOverlayCalculationFailed = "跳跃范围覆盖计算失败"
    override val manualMaximumLyMustBeNumber = "手动最大跳跃距离必须是数字"
    override val manualMaximumLyMustBePositive = "手动最大跳跃距离必须是有限正数"
    override val addWaypointOrDestination = "计算前请添加航点或终点。"
    override val invalidNavigationStop = "导航停靠点无效。"
    override val ansiblexDataUnavailable = "Ansiblex 数据不可用。"

    override fun routeFound(jumps: Int) = "已找到路线：$jumps 跳"
    override fun jumpCount(count: Int) = "$count 跳"
    override fun stargateCount(count: Int) = "$count 星门"
    override fun ansiblexCount(count: Int) = "$count Ansiblex"
    override fun wormholeCount(count: Int) = "$count 虫洞"
    override fun capitalJumpCount(count: Int) = "$count 旗舰跳跃"
    override fun totalDistanceLy(distance: Double) = String.format(Locale.ROOT, "总距离 %.3f LY", distance)
    override fun jumpDistanceLy(distance: Double) = String.format(Locale.ROOT, "%.3f LY", distance)
    override fun overlayIntersection(overlays: Int, systems: Int) = "$overlays 个覆盖的交集：$systems 个星系"
    override fun ansiblexManager(enabled: Int, total: Int) = "Ansiblex 管理器（$enabled/$total）"
    override fun wormholeManager(total: Int) = "虫洞管理器（$total）"
    override fun adjacentDuplicate(systemName: String) = "相邻导航停靠点不能同为 $systemName。"
    override fun navigationStop(role: NavigationStopUiRole, systemName: String) = "${role.chineseLabel} $systemName"
    override fun segmentFailure(from: String, to: String) = "无法计算路段：$from → $to"
    override fun capitalEndpointVerdict(endpoint: String, reason: String) =
        "${translateEndpoint(endpoint)}：${translateEligibilityReason(reason)}"

    private fun translateEndpoint(endpoint: String): String = when (endpoint) {
        "START" -> "起点"
        "DESTINATION" -> "终点"
        else -> endpoint
    }

    private fun translateEligibilityReason(reason: String): String = when (reason) {
        "Jump drives, conduit jumps, and jump bridges cannot be used from Zarzakh" ->
            "无法从 Zarzakh 使用跳跃引擎、管线跳跃或跳桥"
        "No reliable official static rule confirms Zarzakh as a jump-drive destination" ->
            "没有可靠的官方静态规则确认 Zarzakh 可作为跳跃终点"
        "Jump-drive destination is high-security space" -> "跳跃终点位于高安全区"
        "Pochven is excluded by the static jump-drive rules" -> "Pochven 被静态跳跃规则排除"
        "Jove space is excluded by the static jump-drive rules" -> "Jove 空间被静态跳跃规则排除"
        "Wormhole space is excluded by the static jump-drive rules" -> "虫洞空间被静态跳跃规则排除"
        "Abyssal space is excluded by the static jump-drive rules" -> "深渊空间被静态跳跃规则排除"
        "No reliable static origin rule is available for this special system" -> "此特殊星系没有可靠的静态起点规则"
        "No reliable static destination rule is available for this special system" -> "此特殊星系没有可靠的静态终点规则"
        "Unknown origin solar system" -> "未知起点星系"
        "Unknown destination solar system" -> "未知终点星系"
        else -> reason
    }

    private val NavigationStopUiRole.chineseLabel: String
        get() = when (this) {
            NavigationStopUiRole.START -> "起点"
            NavigationStopUiRole.WAYPOINT -> "航点"
            NavigationStopUiRole.DESTINATION -> "终点"
        }
}
