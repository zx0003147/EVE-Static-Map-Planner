package dev.evestaticmapplanner.control

import dev.evestaticmapplanner.core.route.NavigationIntent
import dev.evestaticmapplanner.feature.api.NavigationSnapshot
import dev.evestaticmapplanner.feature.api.RouteActionResult
import dev.evestaticmapplanner.feature.api.RouteActionStatus
import dev.evestaticmapplanner.feature.api.RouteActionTargetId
import dev.evestaticmapplanner.feature.api.RouteIdentity
import dev.evestaticmapplanner.feature.api.RouteKind
import dev.evestaticmapplanner.featurepack.RouteActionHost
import dev.evestaticmapplanner.featurepack.RouteActionUiState
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bridges generic navigation-aware Feature Pack actions into the Mission-only AI control surface. */
internal class FeaturePackMissionNavigationActionAdapter(
    private val host: RouteActionHost,
) : MissionNavigationActionPort {
    override suspend fun listTargets(): List<NavigationActionTargetDto> {
        val selector = action().targetSelector ?: return emptyList()
        return selector.options.map { option ->
            NavigationActionTargetDto(
                characterId = option.id.value,
                label = option.label,
                description = option.description,
                available = option.available,
            )
        }
    }

    override suspend fun send(
        routeIdentity: String,
        intent: NavigationIntent,
        characterId: String,
    ): NavigationActionPortResult {
        val action = action()
        val targetId = RouteActionTargetId(characterId)
        val option = action.targetSelector?.options?.singleOrNull { it.id == targetId && it.available }
            ?: return NavigationActionPortResult(
                NavigationActionExecutionStatus.REJECTED,
                "The selected EVE character is disconnected or unavailable.",
            )
        check(option.id == targetId)
        val snapshot = NavigationSnapshot(
            identity = RouteIdentity(routeIdentity),
            kind = RouteKind.MISSION_NORMAL,
            startSystemId = intent.startSystemId,
            waypointSystemIds = intent.waypointSystemIds,
            destinationSystemId = intent.destinationSystemId,
        )
        val future = host.invokeNavigationWithResult(action.key, snapshot, targetId)
            ?: return NavigationActionPortResult(
                NavigationActionExecutionStatus.REJECTED,
                "The EVE navigation action is busy or unavailable.",
            )
        val result = withContext(Dispatchers.IO) {
            try {
                future.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                RouteActionResult(
                    RouteActionStatus.FAILED,
                    "The EVE navigation response was interrupted; inspect EVE before retrying.",
                )
            } catch (error: ExecutionException) {
                RouteActionResult(RouteActionStatus.FAILED, "The EVE navigation action failed.")
            }
        }
        return NavigationActionPortResult(result.status.toControlStatus(), result.message)
    }

    private fun action(): RouteActionUiState {
        val matches = host.state.value.filter { state ->
            state.supportsNavigationIntent && RouteKind.MISSION_NORMAL in state.supportedRouteKinds
        }
        return when (matches.size) {
            1 -> matches.single()
            0 -> throw ControlPortFailure(ControlErrorCode.APP_NOT_READY, "EVE navigation sending is unavailable")
            else -> throw ControlPortFailure(
                ControlErrorCode.INVALID_ARGUMENT,
                "More than one EVE navigation action is available; automatic action selection is disabled",
            )
        }
    }
}

private fun RouteActionStatus.toControlStatus(): NavigationActionExecutionStatus = when (this) {
    RouteActionStatus.SUCCEEDED -> NavigationActionExecutionStatus.SUCCEEDED
    RouteActionStatus.REJECTED -> NavigationActionExecutionStatus.REJECTED
    RouteActionStatus.FAILED -> NavigationActionExecutionStatus.FAILED
}
