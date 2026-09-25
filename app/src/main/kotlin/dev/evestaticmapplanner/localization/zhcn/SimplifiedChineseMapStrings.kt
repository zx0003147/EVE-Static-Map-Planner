package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.core.map.MapProjectionId
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.localization.MapStrings

internal object SimplifiedChineseMapStrings : MapStrings {
    override val loadingStaticUniverse = "正在加载静态宇宙…"
    override val unableToLoadMap = "无法加载地图"
    override val database = "数据库"
    override val fitMap = "适配地图"
    override val resetView = "重置视图"
    override val renameView = "重命名视图"
    override val viewName = "视图名称"
    override val viewNameValidation = "视图名称不能为空且不能重复。"
    override val toggleProjection = "切换 2D/3D 地图模式"
    override val openEmbeddedAiAssistant = "打开内置 AI 助手"
    override val collapseSidebar = "收起侧边栏"
    override val expandSidebar = "展开侧边栏"
    override val official2DSelected = "已选择官方 2D"
    override val real3DSelected = "已选择真实 3D"
    override val mapOverlays = "地图叠加层"
    override val trackedCharacters = "跟踪角色"
    override val noTrackedCharacters = "没有跟踪角色"
    override val locationUnavailable = "位置不可用"
    override val trackingDisabled = "跟踪已禁用"
    override val locate = "定位"
    override val currentIdentity = "当前身份"
    override val foregroundCharacter = "前台角色"
    override val bindCurrentClient = "绑定客户端"
    override val currentClientBound = "已绑定当前 EVE 客户端"
    override val addTemporaryMarker = "添加临时标记"
    override val addSavedMarker = "添加已保存标记…"
    override val editMarker = "编辑标记…"
    override val savePermanently = "永久保存…"
    override val removeMarker = "移除标记"
    override val markersUnavailable = "标记不可用"
    override val addSharedMarker = "添加共享标记…"
    override val openSharedMarker = "共享标记…"
    override val viewSharedMarker = "查看共享标记…"
    override val addJumpRangeOverlay = "添加跳跃范围覆盖"
    override val setNormalStart = "设为普通路线起点"
    override val addNormalWaypoint = "添加为普通路线航点"
    override val setNormalDestination = "设为普通路线终点"
    override val setCapitalStart = "设为旗舰路线起点"
    override val addCapitalWaypoint = "添加为旗舰路线航点"
    override val setCapitalDestination = "设为旗舰路线终点"
    override val createWormholeConnection = "创建虫洞连接…"
    override val wormholeConnections = "虫洞连接…"
    override val viewerAccess = "仅查看权限"
    override val authenticationRequired = "需要身份验证"
    override val readOnly = "只读"
    override val notConnected = "未连接"
    override val connecting = "正在连接"
    override val temporarilyReadOnly = "暂时只读"
    override val offline = "离线"
    override val accessRemoved = "访问权限已移除"
    override val incompatibleServer = "服务器不兼容"

    override fun projectionLabel(projectionId: MapProjectionId): String = when (projectionId) {
        MapProjectionId.OFFICIAL_2D -> "官方 2D"
        MapProjectionId.REAL_3D -> "真实 3D"
    }

    override fun fallbackSystem(systemId: Int) = "星系 $systemId"
    override fun mapSummary(projection: String, systems: Int, stargateConnections: Int) =
        "$projection：$systems 个星系 · $stargateConnections 条星门连接"
    override fun unavailableSystems(count: Int) = "$count 个不可用"
    override fun routeUnavailable(systemCount: Int, legCount: Int) =
        "路线：$systemCount 个星系 / $legCount 段不可用；请使用真实 3D"
    override fun jumpOverlayUnavailable(count: Int) = "跳跃范围覆盖：$count 个不可用"
    override fun capitalRouteUnavailable(count: Int) = "旗舰路线：$count 段不可用"
    override fun wormholesUnavailable(count: Int) = "虫洞：$count 条不可用"
    override fun focusSwitchedToReal3D(systemName: String) =
        "$systemName 在官方 2D 中不可用，已切换到真实 3D。"
    override fun wormholeConnections(count: Int) = "虫洞连接…（$count）"
    override fun sharedMarkerUnavailable(reason: String) = "添加共享标记…（$reason）"
    override fun characterLocationStatus(status: TrackedCharacterLocationStatus) = when (status) {
        TrackedCharacterLocationStatus.CURRENT -> "当前"
        TrackedCharacterLocationStatus.STALE -> "过期"
        TrackedCharacterLocationStatus.DEGRADED -> "降级"
        TrackedCharacterLocationStatus.UNKNOWN -> "未知"
    }
    override fun trackedCharacterCount(count: Int) = "$trackedCharacters（$count）"
    override fun moreTrackedCharacters(count: Int) = "另有 $count 个"
    override fun currentClientBindingFailed(reason: String) = "无法绑定当前 EVE 客户端：$reason"
}
