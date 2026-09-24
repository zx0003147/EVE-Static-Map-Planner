package dev.evestaticmapplanner.map

import androidx.compose.ui.graphics.Color
import dev.evestaticmapplanner.core.ansiblex.AnsiblexConnection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexAccessStatus
import dev.evestaticmapplanner.core.ansiblex.AnsiblexDirection
import dev.evestaticmapplanner.core.ansiblex.AnsiblexSource
import dev.evestaticmapplanner.core.identity.CurrentIdentityContext
import dev.evestaticmapplanner.core.identity.CurrentIdentitySource
import dev.evestaticmapplanner.core.identity.EveCharacterIdentity
import dev.evestaticmapplanner.core.route.RouteEdgeType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapRouteStyleTest {
    @Test
    fun `route types use their exact visual identity colors`() {
        assertEquals(Color(0xFF42D6F5), ROUTE_STARGATE_COLOR)
        assertEquals(Color(0xFFFF9F43), ROUTE_ANSIBLEX_COLOR)
        assertEquals(Color(0xFF32D6C5), WORMHOLE_PEACOCK_TEAL)
        assertEquals(WORMHOLE_PEACOCK_TEAL, ROUTE_WORMHOLE_COLOR)
        assertEquals(Color(0xFFB388FF), CAPITAL_ROUTE_COLOR)
        assertEquals(listOf(Color(0xFFF4E06D)), MISSION_ROUTE_COLORS)
        assertEquals(Color(0xFFFF5C57), MISSION_CAPITAL_ROUTE_COLOR)
        assertEquals(
            listOf(Color(0xFFFF5C57), Color(0xE6FF5C57), Color(0xCCFF5C57), Color(0xB3FF5C57)),
            MISSION_CAPITAL_COLORS,
        )
    }

    @Test
    fun `active Ansiblex route remains dashed while Stargate route remains solid`() {
        val ansiblex = routeLegRenderStyle(RouteEdgeType.ANSIBLEX)
        val stargate = routeLegRenderStyle(RouteEdgeType.STARGATE)

        assertEquals(ROUTE_ANSIBLEX_COLOR, ansiblex.color)
        assertEquals(4f, ansiblex.strokeWidth)
        assertEquals(listOf(12f, 7f), ansiblex.dashPattern)
        assertEquals(ROUTE_STARGATE_COLOR, stargate.color)
        assertEquals(3f, stargate.strokeWidth)
        assertNull(stargate.dashPattern)
    }

    @Test
    fun `Wormhole route is officially supported with a solid Peacock Teal identity`() {
        val wormhole = routeLegRenderStyle(RouteEdgeType.WORMHOLE)

        assertEquals(WORMHOLE_PEACOCK_TEAL, wormhole.color)
        assertEquals(4f, wormhole.strokeWidth)
        assertNull(wormhole.dashPattern)
    }

    @Test
    fun `available and permission denied Ansiblex links have distinct map styles`() {
        val available = ansiblexNetworkRenderStyle(AnsiblexAccessStatus.AVAILABLE)
        val unavailable = ansiblexNetworkRenderStyle(AnsiblexAccessStatus.ALLIANCE_MISMATCH)

        assertEquals(ANSIBLEX_NETWORK_COLOR, available.color)
        assertEquals(1f, available.alphaMultiplier)
        assertEquals(ANSIBLEX_UNAVAILABLE_NETWORK_COLOR, unavailable.color)
        assertEquals(0.58f, unavailable.alphaMultiplier)
    }

    @Test
    fun `2D Ansiblex visibility keeps allowed links when unavailable links are hidden`() {
        assertAnsiblexVisibilityMatrix(::selectVisible2DAnsiblexConnections)
    }

    @Test
    fun `Real 3D Ansiblex visibility keeps allowed links when unavailable links are hidden`() {
        assertAnsiblexVisibilityMatrix(::selectVisibleReal3DAnsiblexConnections)
    }

    @Test
    fun `ESI and manual identities with the same alliance produce identical 2D and 3D visibility`() {
        val connections = listOf(
            ansiblex("allowed", OWNER_ALLIANCE_ID),
            ansiblex("denied", OTHER_ALLIANCE_ID),
            ansiblex("unknown", null),
        )
        val manual = CurrentIdentityContext.manual(OWNER_ALLIANCE_ID, "Alliance", "ALLY")
        val esi = CurrentIdentityContext(
            source = CurrentIdentitySource.ESI,
            character = EveCharacterIdentity(90_000_001L, "Pilot"),
            allianceId = OWNER_ALLIANCE_ID,
            allianceName = "Alliance",
            allianceTicker = "ALLY",
        )

        listOf(
            ::selectVisible2DAnsiblexConnections,
            ::selectVisibleReal3DAnsiblexConnections,
        ).forEach { selector ->
            assertEquals(selector(connections, true, manual), selector(connections, true, esi))
            assertEquals(selector(connections, false, manual), selector(connections, false, esi))
        }
    }

    private fun assertAnsiblexVisibilityMatrix(
        selector: (List<AnsiblexConnection>, Boolean, CurrentIdentityContext?) -> List<AnsiblexConnection>,
    ) {
        val allowed = ansiblex("allowed", OWNER_ALLIANCE_ID)
        val denied = ansiblex("denied", OTHER_ALLIANCE_ID)
        val unknown = ansiblex("unknown", null)
        val identity = CurrentIdentityContext.manual(OWNER_ALLIANCE_ID)

        assertEquals(listOf(allowed, denied, unknown), selector(listOf(allowed, denied, unknown), true, identity))
        assertEquals(listOf(allowed), selector(listOf(allowed, denied, unknown), false, identity))

        val allowedStyle = ansiblexNetworkRenderStyle(AnsiblexAccessStatus.AVAILABLE)
        val deniedStyle = ansiblexNetworkRenderStyle(AnsiblexAccessStatus.ALLIANCE_MISMATCH)
        val unknownStyle = ansiblexNetworkRenderStyle(AnsiblexAccessStatus.OWNER_UNKNOWN)
        assertEquals(ANSIBLEX_NETWORK_COLOR, allowedStyle.color)
        assertEquals(1f, allowedStyle.alphaMultiplier)
        assertEquals(ANSIBLEX_UNAVAILABLE_NETWORK_COLOR, deniedStyle.color)
        assertEquals(0.58f, deniedStyle.alphaMultiplier)
        assertEquals(ANSIBLEX_UNAVAILABLE_NETWORK_COLOR, unknownStyle.color)
        assertEquals(0.58f, unknownStyle.alphaMultiplier)
    }

    private fun ansiblex(id: String, ownerAllianceId: Long?) = AnsiblexConnection(
        id = id,
        firstSystemId = 1,
        secondSystemId = 2,
        direction = AnsiblexDirection.BIDIRECTIONAL,
        displayName = null,
        notes = null,
        source = AnsiblexSource.MANUAL,
        sourceBatchId = null,
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        ownerAllianceId = ownerAllianceId,
    )

    private companion object {
        const val OWNER_ALLIANCE_ID = 99_000_001L
        const val OTHER_ALLIANCE_ID = 99_000_002L
    }
}
