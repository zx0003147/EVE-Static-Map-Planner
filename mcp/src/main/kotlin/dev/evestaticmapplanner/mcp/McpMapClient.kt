package dev.evestaticmapplanner.mcp

import dev.evestaticmapplanner.control.transport.LocalControlClient
import dev.evestaticmapplanner.control.transport.LocalControlClientResult

internal interface McpMapClient : AutoCloseable {
    suspend fun searchSystem(query: String): LocalControlClientResult
    suspend fun getSystemInfo(systemId: Int): LocalControlClientResult
    suspend fun getSystemMarkers(systemId: Int): LocalControlClientResult
    suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean): LocalControlClientResult
    suspend fun calculateCapitalRoute(startSystemId: Int, destinationSystemId: Int, effectiveRangeLy: Double): LocalControlClientResult
    suspend fun listViews(): LocalControlClientResult = unsupportedViewClientResult()
    suspend fun getCurrentView(): LocalControlClientResult = unsupportedViewClientResult()
    suspend fun getActiveMissions(): LocalControlClientResult
    suspend fun getActiveMissions(viewId: String?): LocalControlClientResult = getActiveMissions()
    suspend fun getMission(missionId: String): LocalControlClientResult
    suspend fun beginMission(title: String): LocalControlClientResult
    suspend fun beginMission(title: String, viewId: String?): LocalControlClientResult = beginMission(title)
    suspend fun createView(label: String?): LocalControlClientResult = unsupportedViewClientResult()
    suspend fun renameView(viewId: String, label: String): LocalControlClientResult = unsupportedViewClientResult()
    suspend fun switchView(viewId: String): LocalControlClientResult = unsupportedViewClientResult()
    suspend fun deleteView(viewId: String): LocalControlClientResult = unsupportedViewClientResult()
    suspend fun createSavedMarker(
        systemId: Int,
        color: String,
        name: String?,
        notes: String?,
        tags: List<String>,
    ): LocalControlClientResult
    suspend fun focusSystem(systemId: Int): LocalControlClientResult
    suspend fun showNormalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ): LocalControlClientResult
    suspend fun showCapitalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ): LocalControlClientResult
    suspend fun removeMissionRoute(missionId: String, routeId: String): LocalControlClientResult
    suspend fun clearMissionRoutes(missionId: String): LocalControlClientResult
    suspend fun showJumpRange(
        missionId: String,
        originSystemId: Int,
        effectiveRangeLy: Double,
        label: String?,
    ): LocalControlClientResult
    suspend fun removeJumpRange(missionId: String, jumpRangeId: String): LocalControlClientResult
    suspend fun clearMissionJumpRanges(missionId: String): LocalControlClientResult
    suspend fun addMissionMarker(
        missionId: String,
        systemId: Int,
        role: String,
        label: String?,
        notes: String?,
        colorOverride: String?,
    ): LocalControlClientResult
    suspend fun removeMissionMarker(missionId: String, markerId: String): LocalControlClientResult
    suspend fun clearMissionMarkers(missionId: String): LocalControlClientResult
    suspend fun fitMission(missionId: String): LocalControlClientResult
    suspend fun clearMission(missionId: String): LocalControlClientResult
}

internal class LocalMcpMapClient(
    private val client: LocalControlClient = LocalControlClient(),
) : McpMapClient {
    override suspend fun searchSystem(query: String) = client.searchSystem(query)
    override suspend fun getSystemInfo(systemId: Int) = client.getSystemInfo(systemId)
    override suspend fun getSystemMarkers(systemId: Int) = client.getSystemMarkers(systemId)
    override suspend fun calculateNormalRoute(startSystemId: Int, destinationSystemId: Int, useAnsiblex: Boolean) =
        client.calculateNormalRoute(startSystemId, destinationSystemId, useAnsiblex)
    override suspend fun calculateCapitalRoute(startSystemId: Int, destinationSystemId: Int, effectiveRangeLy: Double) =
        client.calculateCapitalRoute(startSystemId, destinationSystemId, effectiveRangeLy)
    override suspend fun listViews() = client.listViews()
    override suspend fun getCurrentView() = client.getCurrentView()
    override suspend fun getActiveMissions() = client.getActiveMissions()
    override suspend fun getActiveMissions(viewId: String?) = client.getActiveMissions(viewId)
    override suspend fun getMission(missionId: String) = client.getMission(missionId)
    override suspend fun beginMission(title: String) = client.beginMission(title)
    override suspend fun beginMission(title: String, viewId: String?) = client.beginMission(title, viewId)
    override suspend fun createView(label: String?) = client.createView(label)
    override suspend fun renameView(viewId: String, label: String) = client.renameView(viewId, label)
    override suspend fun switchView(viewId: String) = client.switchView(viewId)
    override suspend fun deleteView(viewId: String) = client.deleteView(viewId)
    override suspend fun createSavedMarker(
        systemId: Int,
        color: String,
        name: String?,
        notes: String?,
        tags: List<String>,
    ) = client.createSavedMarker(systemId, name, notes, color, tags)
    override suspend fun focusSystem(systemId: Int) = client.focusSystem(systemId)
    override suspend fun showNormalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        useAnsiblex: Boolean,
    ) = client.showNormalRoute(missionId, startSystemId, destinationSystemId, useAnsiblex)
    override suspend fun showCapitalRoute(
        missionId: String,
        startSystemId: Int,
        destinationSystemId: Int,
        effectiveRangeLy: Double,
    ) = client.showCapitalRoute(missionId, startSystemId, destinationSystemId, effectiveRangeLy)
    override suspend fun removeMissionRoute(missionId: String, routeId: String) = client.removeMissionRoute(missionId, routeId)
    override suspend fun clearMissionRoutes(missionId: String) = client.clearMissionRoutes(missionId)
    override suspend fun showJumpRange(missionId: String, originSystemId: Int, effectiveRangeLy: Double, label: String?) =
        client.showJumpRange(missionId, originSystemId, effectiveRangeLy, label)
    override suspend fun removeJumpRange(missionId: String, jumpRangeId: String) =
        client.removeJumpRange(missionId, jumpRangeId)
    override suspend fun clearMissionJumpRanges(missionId: String) = client.clearMissionJumpRanges(missionId)
    override suspend fun addMissionMarker(
        missionId: String,
        systemId: Int,
        role: String,
        label: String?,
        notes: String?,
        colorOverride: String?,
    ) = client.addMissionMarker(missionId, systemId, role, label, notes, colorOverride)
    override suspend fun removeMissionMarker(missionId: String, markerId: String) =
        client.removeMissionMarker(missionId, markerId)
    override suspend fun clearMissionMarkers(missionId: String) = client.clearMissionMarkers(missionId)
    override suspend fun fitMission(missionId: String) = client.fitMission(missionId)
    override suspend fun clearMission(missionId: String) = client.clearMission(missionId)
    override fun close() = client.close()
}

private fun unsupportedViewClientResult() = LocalControlClientResult.Failure(
    dev.evestaticmapplanner.control.transport.LocalControlClientError(
        dev.evestaticmapplanner.control.transport.LocalControlClientErrorCode.INVALID_ARGUMENT,
        "Planning Views are not supported by this client",
    ),
)
