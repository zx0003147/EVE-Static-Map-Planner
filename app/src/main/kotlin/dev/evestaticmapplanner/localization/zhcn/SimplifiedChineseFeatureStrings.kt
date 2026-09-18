package dev.evestaticmapplanner.localization.zhcn

import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.SavedMarkerChildType
import dev.evestaticmapplanner.data.ansiblex.AnsiblexImportMode
import dev.evestaticmapplanner.data.ansiblex.ImportDiagnostic
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.PlannerToolRisk
import dev.evestaticmapplanner.feature.api.TrackedCharacterLocationStatus
import dev.evestaticmapplanner.localization.AiAssistantStrings
import dev.evestaticmapplanner.localization.AiVoiceStatus
import dev.evestaticmapplanner.localization.AnsiblexMessage
import dev.evestaticmapplanner.localization.AnsiblexStrings
import dev.evestaticmapplanner.localization.MarkerMessage
import dev.evestaticmapplanner.localization.MarkerStrings
import dev.evestaticmapplanner.localization.MiniMapStrings
import dev.evestaticmapplanner.localization.SharedMapStrings
import dev.evestaticmapplanner.localization.WormholeMessage
import dev.evestaticmapplanner.localization.WormholeStrings
import dev.evestaticmapplanner.shared.api.SharedMapError
import dev.evestaticmapplanner.shared.model.SharedConnectionState
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import dev.evestaticmapplanner.shared.model.SharedWorkspaceRole
import dev.evestaticmapplanner.preferences.MiniMapFollowMode

internal object SimplifiedChineseAiAssistantStrings : AiAssistantStrings {
    override val title = "内置 AI 助手"
    override val chats = "对话"
    override val newChat = "+ 新建对话"
    override val chatLifetimeHelper = "对话会保留到规划器关闭。"
    override val openAiSettings = "打开 AI 设置"
    override val startConversation = "开始与规划助手对话。"
    override val messagePlaceholder = "输入消息……"
    override val stopRecordingAndTranscribe = "停止录音并转写"
    override val cancelTranscription = "取消转写"
    override val startVoiceInput = "开始语音输入"
    override val stopGenerating = "停止生成"
    override val sendMessage = "发送消息"
    override val stopState = "停止"
    override val sendState = "发送"
    override val renameChat = "重命名对话"
    override val collapseChatSidebar = "收起对话侧栏"
    override val expandChatSidebar = "展开对话侧栏"
    override val leftChevron = "向左箭头"
    override val rightChevron = "向右箭头"
    override val you = "你"
    override val ai = "AI"
    override val waitingForConfirmation = "正在等待确认……"
    override val thinking = "正在思考……"
    override val stopReading = "停止朗读"
    override val readAloud = "朗读"
    override val confirmAiAction = "确认 AI 操作"
    override val action = "操作"
    override val target = "目标"
    override val risk = "风险"
    override val effect = "影响"
    override val allow = "允许"

    override fun riskLabel(risk: PlannerToolRisk) = when (risk) {
        PlannerToolRisk.READ_ONLY -> "只读"
        PlannerToolRisk.TEMPORARY_UI -> "临时地图变更"
        PlannerToolRisk.PERSISTENT_WRITE -> "永久写入"
        PlannerToolRisk.DESTRUCTIVE_WRITE -> "破坏性变更"
        PlannerToolRisk.EXTERNAL_ACTION -> "外部操作"
    }

    override fun credentialSource(source: AiCredentialSource) = when (source) {
        AiCredentialSource.SECURE_STORAGE -> "安全存储"
        AiCredentialSource.SESSION_ONLY -> "仅本次会话"
        AiCredentialSource.ENVIRONMENT -> "环境变量"
    }

    override fun providerDescription(providerName: String?, modelId: String?, source: AiCredentialSource?) = when {
        providerName == null -> "尚未配置 AI 提供商。"
        source == null -> "提供商：$providerName · 尚未配置 AI API Key。"
        else -> "$providerName · $modelId · ${credentialSource(source)}"
    }

    override fun providerAction(providerConfigured: Boolean) =
        if (providerConfigured) "请先配置 AI API Key。" else "请先配置 AI 提供商。"

    override fun voiceStatus(status: AiVoiceStatus) = when (status) {
        AiVoiceStatus.CANCELLED -> "语音操作已取消。"
        AiVoiceStatus.RECORDING -> "正在录音……再次点击即可停止并转写。"
        AiVoiceStatus.TRANSCRIBING -> "正在转写……"
        AiVoiceStatus.TRANSCRIPTION_COMPLETE -> "转写完成。"
        AiVoiceStatus.TRANSCRIPTION_CANCELLED -> "转写已取消。"
        AiVoiceStatus.PREPARING_SPEECH -> "正在准备语音……"
        AiVoiceStatus.PLAYING -> "正在播放……"
    }
    override fun voiceControllerMessage(message: String) = when (message) {
        "Voice activity cancelled." -> "语音活动已取消。"
        "Voice Input is off. Enable it in AI Features settings." -> "语音输入已关闭，请在 AI 功能设置中启用。"
        "Recording… click again to transcribe." -> "正在录音……再次点击即可转写。"
        "Transcribing…" -> "正在转写……"
        "Transcription complete." -> "转写完成。"
        "Transcription cancelled." -> "转写已取消。"
        "Voice Output is off. Enable it in AI Features settings." -> "语音输出已关闭，请在 AI 功能设置中启用。"
        "Preparing speech…" -> "正在准备语音……"
        "Playing…" -> "正在播放……"
        else -> message
    }
    override fun contextNotice(message: String) = when (message) {
        "Provider changed. New AI context started." -> "提供商已更改，已启动新的 AI 上下文。"
        else -> message
    }
}

internal object SimplifiedChineseMarkerStrings : MarkerStrings {
    override val managerTitle = "标记管理"
    override val savedMarkerManager = "已保存标记管理"
    override val searchSavedMarkers = "搜索已保存标记……"
    override val loadingSavedMarkers = "正在加载已保存标记……"
    override val noSavedMarkers = "没有已保存标记。"
    override val noSavedMarkersMatch = "没有符合搜索条件的已保存标记。"
    override val showOnMap = "在地图上显示"
    override val removeSavedMarkerTitle = "移除已保存标记？"
    override val markerOperationFailed = "标记操作失败"
    override val removing = "正在移除……"
    override val system = "星系"
    override val name = "名称"
    override val notes = "备注"
    override val color = "颜色"
    override val addSavedMarker = "添加已保存标记"
    override val editMarker = "编辑标记"
    override val searchSystem = "搜索星系"
    override val saving = "正在保存……"
    override val save = "保存"
    override val tags = "标签"
    override val noTagsAssigned = "尚未分配标签。"
    override val allTagsAssigned = "已分配全部标签"
    override val addTag = "+ 添加标签"
    override val createdByAi = "由 AI 创建"
    override val unknown = "未知"
    override val clearTemporaryTitle = "清除临时标记？"

    override fun fallbackSystem(systemId: Int) = "星系 $systemId"
    override fun removeSavedMarker(systemName: String) = "要从 $systemName 移除已保存标记吗？"
    override fun removeSavedMarkerCannotUndo(systemName: String) = "要从 $systemName 移除已保存标记吗？此操作无法撤销。"
    override fun selectedSystem(systemName: String, systemId: Int) = "已选择：$systemName · $systemId"
    override fun markerColor(color: MarkerColor) = when (color) {
        MarkerColor.RED -> "红色"
        MarkerColor.ORANGE -> "橙色"
        MarkerColor.YELLOW -> "黄色"
        MarkerColor.GREEN -> "绿色"
        MarkerColor.BLUE -> "蓝色"
        MarkerColor.PURPLE -> "紫色"
        MarkerColor.WHITE -> "白色"
    }
    override fun childType(type: SavedMarkerChildType?, fallback: String) = when (type?.key) {
        "staging" -> "集结"
        "rally" -> "会合"
        "danger" -> "危险"
        "logistics" -> "后勤"
        "home" -> "基地"
        "backup" -> "备用"
        "industrial" -> "工业"
        "strategic" -> "战略"
        "fortizar" -> "Fortizar"
        "keepstar" -> "Keepstar"
        else -> fallback
    }
    override fun databaseUnavailable(technicalDetail: String) = "已保存标记不可用。\n$technicalDetail"
    override fun clearTemporaryMessage(count: Int) = "要移除全部 $count 个临时标记吗？已保存标记不会改变。"

    override fun message(id: MarkerMessage, systemId: Int?, tag: String?, technicalDetail: String?) = appendDetail(
        when (id) {
            MarkerMessage.CREATE_TEMPORARY_FAILED -> "无法创建临时标记。"
            MarkerMessage.OPERATION_IN_PROGRESS -> "星系 $systemId 的标记操作正在进行。"
            MarkerMessage.MARKER_MISSING -> "星系 $systemId 的标记已不存在。"
            MarkerMessage.SAVED_CANNOT_UPDATE_AS_TEMPORARY -> "无法把星系 $systemId 的已保存标记作为临时标记更新。"
            MarkerMessage.SAVED_CANNOT_REMOVE_AS_TEMPORARY -> "无法把星系 $systemId 的已保存标记作为临时标记移除。"
            MarkerMessage.TEMPORARY_OPERATION_IN_PROGRESS -> "仍有临时标记操作正在进行。"
            MarkerMessage.CREATE_SAVED_FAILED -> "无法创建已保存标记。"
            MarkerMessage.UPDATE_SAVED_FAILED -> "无法更新已保存标记。"
            MarkerMessage.REMOVE_SAVED_FAILED -> "无法移除已保存标记。"
            MarkerMessage.TAG_ALREADY_ASSIGNED -> "已保存标记已分配标签 $tag。"
            MarkerMessage.ADD_TAG_FAILED -> "无法添加已保存标记标签。"
            MarkerMessage.TAG_MISSING -> "已保存标记标签已不存在。"
            MarkerMessage.REMOVE_TAG_FAILED -> "无法移除已保存标记标签。"
            MarkerMessage.STILL_LOADING -> "已保存标记仍在加载。"
            MarkerMessage.ALREADY_SAVED -> "星系 $systemId 的标记已经保存。"
            MarkerMessage.SAVE_TEMPORARY_FAILED -> "无法永久保存临时标记。"
            MarkerMessage.TEMPORARY_CANNOT_USE_SAVED_OPERATION -> "星系 $systemId 的临时标记不能执行已保存标记操作。"
            MarkerMessage.SYSTEM_ALREADY_MARKED -> "星系 $systemId 已有标记。"
            MarkerMessage.TEMPORARY_CONFLICT -> "该星系已有临时标记，请改用该标记的“永久保存”。"
            MarkerMessage.SAVED_CONFLICT -> "该星系已有标记。"
        },
        technicalDetail,
    )
}

internal object SimplifiedChineseSharedMapStrings : SharedMapStrings {
    override val sharedMarkerManager = "共享标记管理"
    override val workspace = "工作区"
    override val none = "无"
    override val status = "状态"
    override val role = "角色"
    override val markers = "标记"
    override val accessRemoved = "共享地图访问权限已移除。"
    override val staleDataReadOnly = "当前显示旧的共享标记数据，编辑已禁用。"
    override val readOnlyUntilOnline = "连接恢复在线前，共享地图为只读。"
    override val searchMarker = "搜索星系、标记或标签"
    override val system = "星系"
    override val updated = "更新时间"
    override val name = "名称"
    override val refresh = "刷新"
    override val noSnapshot = "暂无共享标记快照。"
    override val noMarkers = "此工作区中没有共享标记。"
    override val noMarkersMatch = "没有符合搜索条件的共享标记。"
    override val focus = "定位"
    override val view = "查看"
    override val edit = "编辑"
    override val addSharedMarker = "添加共享标记"
    override val editSharedMarker = "编辑共享标记"
    override val sharedMarker = "共享标记"
    override val deleteSharedMarkerTitle = "删除共享标记？"
    override val deleting = "正在删除……"
    override val saving = "正在保存……"
    override val color = "颜色"
    override val tags = "标签"
    override val updatedBy = "更新者"
    override val solarSystem = "星系"
    override val notes = "备注"
    override val tagsHelper = "使用逗号或空格分隔；最多 9 个小写标签"
    override val temporarilyUnavailable = "共享地图暂时不可用，或你的角色只有只读权限。"
    override val markerMissing = "此共享标记已不存在。"
    override val markerChanged = "其他用户已更改此共享标记。"
    override val reloadBeforeEditing = "请先重新加载服务器上的最新版本。"
    override val reloadLatest = "重新加载最新版本"
    override val saveSharedMarker = "保存共享标记"
    override val saveChanges = "保存更改"
    override val deleteSharedMarker = "删除共享标记"
    override val reloadLatestTitle = "重新加载最新共享标记？"
    override val discardUnsavedChanges = "未保存的更改将被丢弃。"
    override val commonTags = "常用标签"
    override val membersTitle = "共享地图成员"
    override val loadingMembers = "正在加载成员……"
    override val dismiss = "忽略"
    override val active = "有效"
    override val revoked = "已撤销"
    override val addMember = "添加成员……"
    override val createInvite = "创建邀请"
    override val removeMemberTitle = "移除成员？"
    override val displayName = "显示名称"
    override val initialRole = "初始角色"
    override val adding = "正在添加……"
    override val oneTimeInviteTitle = "一次性共享地图邀请"
    override val oneTimeInviteHelp = "此邀请只显示一次，请通过可信渠道发送。"
    override val invite = "邀请"
    override val copied = "已复制到剪贴板。"
    override val copy = "复制"
    override val routePublishedToWeb = "路线已发布到 Web。"
    override val operationFailed = "无法完成共享地图操作。"

    override fun workspaceLabel(name: String) = "$workspace：$name"
    override fun statusLabel(value: String) = "$status：$value"
    override fun roleLabel(value: String) = "$role：$value"
    override fun markersLabel(count: Int) = "$markers：$count"
    override fun connectionState(state: SharedConnectionState, stale: Boolean) = when (state) {
        SharedConnectionState.DISCONNECTED -> "未连接"
        SharedConnectionState.CONNECTING -> "正在连接"
        SharedConnectionState.ONLINE -> "在线"
        SharedConnectionState.DEGRADED -> "连接降级"
        SharedConnectionState.OFFLINE -> "离线"
        SharedConnectionState.AUTH_REQUIRED -> "需要身份验证"
        SharedConnectionState.FORBIDDEN -> "访问被拒绝"
        SharedConnectionState.PROTOCOL_UNSUPPORTED -> "协议不兼容"
    } + if (stale) " · 数据已过期" else ""
    override fun role(role: SharedWorkspaceRole) = when (role) {
        SharedWorkspaceRole.ADMIN -> "管理员"
        SharedWorkspaceRole.EDITOR -> "编辑者"
        SharedWorkspaceRole.VIEWER -> "查看者"
    }
    override fun markerColor(color: SharedMarkerColor) = when (color) {
        SharedMarkerColor.RED -> "红色"
        SharedMarkerColor.ORANGE -> "橙色"
        SharedMarkerColor.YELLOW -> "黄色"
        SharedMarkerColor.GREEN -> "绿色"
        SharedMarkerColor.BLUE -> "蓝色"
        SharedMarkerColor.PURPLE -> "紫色"
        SharedMarkerColor.WHITE -> "白色"
    }
    override fun deleteSharedMarker(name: String, systemName: String) = "要从 $systemName 删除共享标记“$name”吗？"
    override fun memberCount(count: Int) = "成员：$count"
    override fun removeMember(displayName: String, workspaceName: String) =
        "要从 $workspaceName 移除 $displayName 吗？该成员的设备将立即失去访问权限。"
    override fun error(error: SharedMapError): String = when (error) {
        is SharedMapError.Forbidden -> "你已无权更改共享标记。"
        is SharedMapError.Authentication -> "更改共享标记前需要身份验证。"
        is SharedMapError.MarkerAlreadyExists -> "该星系已有共享标记，请重新加载最新快照。"
        is SharedMapError.MarkerVersionConflict -> markerChanged
        is SharedMapError.InvalidArgument -> "共享标记参数无效。\n${error.message}"
        is SharedMapError.RateLimited -> "请求过多，请稍后重试。"
        is SharedMapError.Network -> "无法连接共享地图服务器，更改尚未确认。"
        is SharedMapError.Server -> "共享地图服务器无法完成此操作。"
        is SharedMapError.NotFound -> markerMissing
        is SharedMapError.MemberVersionConflict -> "其他管理员已更改此成员，请重新加载成员列表。"
        is SharedMapError.LastAdminRequired -> "工作区必须至少保留一名有效管理员。"
        is SharedMapError.IdempotencyResponseNotReplayable -> "邀请已创建，但无法再次显示其一次性密钥。请撤销后重新创建。"
        is SharedMapError.Protocol -> "共享地图协议错误。\n${error.message}"
        is SharedMapError.InvalidResponse -> "共享地图服务器返回了无效响应。"
        is SharedMapError.InvalidConfiguration -> "共享地图配置无效。\n${error.message}"
    }
    override fun unknownSystem(systemId: Int) = "未知星系（$systemId）"
    override fun statusMessage(message: String?): String? = when (message) {
        null -> null
        "Use an invite to connect to Shared Map." -> "使用邀请连接共享地图。"
        "Connecting to Shared Map…" -> "正在连接共享地图……"
        "The Device Token could not be stored securely." -> "无法安全存储设备令牌。"
        "The Workspace selection could not be saved." -> "无法保存工作区选择。"
        "Loading Shared Map Workspace…" -> "正在加载共享地图工作区……"
        "Disconnected from Shared Map." -> "已断开共享地图连接。"
        "This Device Token no longer has access to the selected Workspace." -> "此设备令牌已无权访问所选工作区。"
        "Shared Map is connected." -> "共享地图已连接。"
        "Authentication is required to reconnect Shared Map." -> "重新连接共享地图需要身份验证。"
        "Access to this Shared Map Workspace was removed." -> "此共享地图工作区的访问权限已移除。"
        "The selected Shared Map Workspace is unavailable." -> "所选共享地图工作区不可用。"
        "Use HTTPS for remote Shared Map servers; HTTP is allowed only for localhost." ->
            "远程共享地图服务器必须使用 HTTPS；只有 localhost 可以使用 HTTP。"
        "The saved Device Token is missing or unreadable. Use a new invite to reconnect." ->
            "已保存的设备令牌缺失或无法读取，请使用新邀请重新连接。"
        else -> message
    }
}

internal object SimplifiedChineseWormholeStrings : WormholeStrings {
    override val managerTitle = "虫洞管理"
    override val sessionOnlyHelp = "虫洞仅在当前应用会话中有效。"
    override val addWormhole = "添加虫洞"
    override val from = "起点"
    override val to = "终点"
    override val currentWormholes = "当前虫洞"
    override val noActiveConnections = "没有有效的虫洞连接"
    override val clearAll = "全部清除"
    override val clearAllTitle = "清除所有虫洞？"
    override val clearAllMessage = "这会移除当前应用会话中的所有临时虫洞连接。"
    override val createWormhole = "创建虫洞"
    override fun connectionCount(count: Int) = "当前会话中有 $count 条连接"
    override fun connectionsTitle(systemName: String) = "虫洞连接 — $systemName"
    override fun fallbackSystem(systemId: Int) = "星系 $systemId"
    override fun message(id: WormholeMessage, count: Int?, technicalDetail: String?) = appendDetail(
        when (id) {
            WormholeMessage.ADDED -> "已添加虫洞"
            WormholeMessage.REMOVED -> "已移除虫洞"
            WormholeMessage.MISSING -> "虫洞连接已不存在"
            WormholeMessage.DUPLICATE -> "虫洞连接已存在"
            WormholeMessage.SAME_ENDPOINT -> "虫洞两端必须是不同星系"
            WormholeMessage.INVALID_SELECTION -> "请选择虫洞的两个端点"
            WormholeMessage.CLEARED -> "已清除 $count 条虫洞连接"
            WormholeMessage.LOAD_FAILED -> "无法加载星系。"
            WormholeMessage.SEARCH_FAILED -> "星系搜索失败。"
        },
        technicalDetail,
    )
}

internal object SimplifiedChineseAnsiblexStrings : AnsiblexStrings {
    override val managerTitle = "Ansiblex 管理"
    override val importCsvJson = "导入 CSV / JSON"
    override val working = "正在处理……"
    override val importAndPreview = "导入并预览"
    override val discard = "丢弃"
    override val manualAdd = "手动添加"
    override val fromNameOrId = "起点名称或 ID"
    override val toNameOrId = "终点名称或 ID"
    override val connectionNameOptional = "连接名称（可选）"
    override val notesOptional = "备注（可选）"
    override val bidirectional = "双向"
    override val fromTo = "起点 → 终点"
    override val addConnection = "添加连接"
    override val connections = "连接"
    override val clearImported = "清除导入数据"
    override val dangerZone = "危险操作"
    override val clearAllAnsiblex = "清除全部 Ansiblex"
    override val deleteAllTitle = "删除全部 Ansiblex 数据？"
    override val deleteImportedTitle = "删除导入的 Ansiblex 数据？"
    override val deleteAllWarning = "这会从 user.db 永久删除 IMPORT 和 MANUAL 连接，且无法撤销。"
    override val deleteImportedWarning = "这只会删除 source=IMPORT 的连接，并保留 MANUAL 连接。"
    override val typeDeleteManual = "输入 DELETE MANUAL 以确认："
    override val deleteEverything = "全部删除"
    override val fileChooserTitle = "选择模拟或用户维护的 Ansiblex CSV/JSON"
    override val fileChooserFilter = "Ansiblex CSV 或 JSON"

    override fun importMode(mode: AnsiblexImportMode) = when (mode) {
        AnsiblexImportMode.MERGE -> "合并（MERGE）"
        AnsiblexImportMode.REPLACE -> "替换（REPLACE）"
    }
    override fun summary(enabled: Int, total: Int, databasePath: String) = "$enabled 个已启用 / 共 $total 个 · $databasePath"
    override fun previewCounts(rows: Int, valid: Int, invalid: Int, duplicates: Int) =
        "行数 $rows · 有效 $valid · 无效 $invalid · 重复 $duplicates"
    override fun previewChanges(additions: Int, updates: Int, unchanged: Int, removals: Int) =
        "新增 $additions  更新 $updates  未变化 $unchanged  删除 $removals"
    override fun diagnostic(diagnostic: ImportDiagnostic): String {
        val message = when (diagnostic.code) {
            "UNSUPPORTED_FILE_TYPE" -> "仅支持 .csv 和 .json Ansiblex 导入文件。"
            "UNKNOWN_CSV_COLUMN" -> "CSV 包含未知列。"
            "MISSING_FROM_COLUMN" -> "CSV 需要 from_system_id 或 from_system_name。"
            "MISSING_TO_COLUMN" -> "CSV 需要 to_system_id 或 to_system_name。"
            "INVALID_SYSTEM_ID" -> "星系 ID 必须是正整数。"
            "INVALID_ENABLED" -> "enabled 必须为 true 或 false。"
            "BAD_CSV" -> "无法解析 CSV。"
            "UNSUPPORTED_FORMAT_VERSION" -> "不支持此 JSON format_version。"
            "BAD_JSON" -> "无法解析 JSON。"
            "EMPTY_IMPORT" -> "Ansiblex 导入文件不包含连接行。"
            "MISSING_ENDPOINT" -> "端点需要星系 ID 或精确星系名称。"
            "UNKNOWN_SYSTEM_ID" -> "未知星系 ID。"
            "UNKNOWN_SYSTEM_NAME" -> "星系名称未知或不唯一。"
            "ENDPOINT_MISMATCH" -> "端点的星系 ID 与名称指向不同星系。"
            "INVALID_DIRECTION" -> "direction 必须为 BIDIRECTIONAL 或 FORWARD。"
            "SELF_LOOP" -> "Ansiblex 连接不能连接星系自身。"
            "CONFLICTING_DUPLICATE" -> "重复连接包含冲突值。"
            "DUPLICATE" -> "已忽略内容相同的重复连接。"
            "MANUAL_SOURCE_CONFLICT" -> "导入数据不能覆盖手动连接。"
            else -> diagnostic.message
        }
        return "${diagnostic.rowNumber?.let { "第 $it 行：" }.orEmpty()}$message"
    }
    override fun direction(direction: AnsiblexDirection) = when (direction) {
        AnsiblexDirection.BIDIRECTIONAL -> "双向"
        AnsiblexDirection.FIRST_TO_SECOND -> "第一端 → 第二端"
        AnsiblexDirection.SECOND_TO_FIRST -> "第二端 → 第一端"
    }
    override fun source(source: AnsiblexSource) = when (source) {
        AnsiblexSource.IMPORT -> "导入"
        AnsiblexSource.MANUAL -> "手动"
    }
    override fun message(
        id: AnsiblexMessage,
        count: Int?,
        updatedCount: Int?,
        removedCount: Int?,
        value: String?,
        technicalDetail: String?,
    ) = appendDetail(
        when (id) {
            AnsiblexMessage.PREVIEW_FAILED -> "无法预览导入。"
            AnsiblexMessage.APPLY_FAILED -> "无法应用导入。"
            AnsiblexMessage.IMPORT_APPLIED -> "已应用 $count 个新增、$updatedCount 个更新、$removedCount 个删除"
            AnsiblexMessage.UNKNOWN_FROM -> "起点星系未知或不唯一：$value"
            AnsiblexMessage.UNKNOWN_TO -> "终点星系未知或不唯一：$value"
            AnsiblexMessage.MANUAL_ADDED -> "已添加手动连接"
            AnsiblexMessage.ADD_FAILED -> "无法添加连接。"
            AnsiblexMessage.CONNECTION_MISSING -> "连接已不存在"
            AnsiblexMessage.ENABLED -> "连接已启用"
            AnsiblexMessage.DISABLED -> "连接已禁用"
            AnsiblexMessage.DELETED -> "连接已删除"
            AnsiblexMessage.CLEARED_IMPORTED -> "已清除 $count 条导入连接"
            AnsiblexMessage.CLEARED_ALL -> "已清除 $count 条 Ansiblex 连接"
            AnsiblexMessage.UPDATE_FAILED -> "无法更新连接。"
        },
        technicalDetail,
    )
}

private fun appendDetail(message: String, detail: String?): String =
    detail?.takeIf { it.isNotBlank() && it != message }?.let { "$message\n$it" } ?: message

internal object SimplifiedChineseMiniMapStrings : MiniMapStrings {
    override val title = "EVE 小地图"
    override val selectCharacter = "选择角色"
    override val noTrackedCharacters = "没有可跟踪角色"
    override val automaticFollow = "自动 — 跟随前台 EVE 客户端"
    override val pinnedFollow = "固定 — 保持当前角色"
    override val options = "小地图选项"
    override val showDiagnostics = "显示诊断信息"
    override val hideDiagnostics = "隐藏诊断信息"
    override val bindCurrentClient = "绑定当前 EVE 客户端……"
    override val bindPrompt = "将此 EVE 客户端临时绑定到："
    override val unknownCharacter = "未知角色"
    override val diagnostics = "诊断信息"
    override val never = "从未"
    override val none = "无"
    override val noCharacterSelected = "未选择角色"
    override val locationUnavailable = "位置不可用"
    override val unknownSystem = "未知星系"
    override fun followMode(mode: MiniMapFollowMode) = when (mode) {
        MiniMapFollowMode.AUTO -> "自动"
        MiniMapFollowMode.PINNED -> "固定"
    }
    override fun portraitDescription(characterName: String) = "$characterName 头像"
    override fun trackingSummary(total: Int, tracked: Int, current: Int, stale: Int, degraded: Int, unknown: Int) =
        "跟踪 $tracked/$total · 当前 $current · 过期 $stale · 降级 $degraded · 未知 $unknown"
    override fun validationSummary(lastValidatedAt: String, errorCategory: String) =
        "上次验证 $lastValidatedAt · 错误 $errorCategory"
    override fun routeScope(hops: Int, includeAnsiblex: Boolean) =
        "$hops 跳 · Ansiblex ${if (includeAnsiblex) "已包含" else "已排除"}"
    override fun status(status: TrackedCharacterLocationStatus) = when (status) {
        TrackedCharacterLocationStatus.CURRENT -> "当前"
        TrackedCharacterLocationStatus.STALE -> "过期"
        TrackedCharacterLocationStatus.DEGRADED -> "降级"
        TrackedCharacterLocationStatus.UNKNOWN -> "未知"
    }
    override fun locationLine(systemName: String, status: TrackedCharacterLocationStatus) =
        if (status == TrackedCharacterLocationStatus.STALE) "上次位置：$systemName · ${status(status)}"
        else "$systemName · ${status(status)}"
    override fun diagnosticText(message: String): String = when (message) {
        "Waiting for a foreground EVE client" -> "正在等待前台 EVE 客户端"
        "Previously followed character is no longer authorized for tracking" -> "之前跟随的角色已无跟踪授权"
        "Character is not authorized for tracking" -> "角色未授权跟踪"
        "No foreground window has been observed" -> "尚未检测到前台窗口"
        "The foreground window is not a verified EVE game client" -> "前台窗口不是已验证的 EVE 游戏客户端"
        "The EVE client session identity is incomplete" -> "EVE 客户端会话身份不完整"
        "Following manual EVE client session binding" -> "正在跟随手动绑定的 EVE 客户端会话"
        "Matched a manual EVE client session binding" -> "已匹配手动绑定的 EVE 客户端会话"
        "Matched foreground EVE character exactly" -> "已精确匹配前台 EVE 角色"
        "Foreground EVE character is not authorized for tracking; retaining the previous character" -> "前台 EVE 角色未授权跟踪；保留之前的角色"
        "Foreground EVE client character is unknown; retaining the previous character" -> "前台 EVE 客户端角色未知；保留之前的角色"
        "EVE Launcher is foreground; retaining the previous character" -> "EVE 启动器位于前台；保留之前的角色"
        "Non-EVE window is foreground; retaining the previous character" -> "非 EVE 窗口位于前台；保留之前的角色"
        "Foreground window identity is uncertain; retaining the previous character" -> "无法确认前台窗口身份；保留之前的角色"
        "Rapid client reversal detected; waiting briefly for a stable foreground" -> "检测到客户端快速切换；正等待前台窗口稳定"
        "Current EVE client session bound" -> "已绑定当前 EVE 客户端会话"
        "Foreground client detection is unavailable on this platform" -> "此平台不支持前台客户端检测"
        "No tracked characters\nConnect a character in ESI Pack first." -> "没有可跟踪角色\n请先在 ESI 功能包中连接角色。"
        "Select a character to follow" -> "选择要跟随的角色"
        "Followed character is unavailable" -> "跟随的角色不可用"
        "Character location is outside the current map projection" -> "角色位置不在当前地图投影范围内"
        else -> when {
            message.startsWith("Tracking is disabled for ") -> "已禁止跟踪 ${message.removePrefix("Tracking is disabled for ")}"
            message.startsWith("Location unavailable for ") -> "${message.removePrefix("Location unavailable for ")} 的位置不可用"
            else -> message
        }
    }
}
