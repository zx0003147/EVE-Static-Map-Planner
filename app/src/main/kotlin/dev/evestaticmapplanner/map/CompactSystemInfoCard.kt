package dev.evestaticmapplanner.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.jump.JumpOverlayUiState
import dev.evestaticmapplanner.route.RoutePlannerUiState
import dev.evestaticmapplanner.core.marker.Marker
import dev.evestaticmapplanner.core.marker.MarkerColor
import dev.evestaticmapplanner.core.marker.MarkerPersistence
import dev.evestaticmapplanner.marker.markerColor
import dev.evestaticmapplanner.feature.api.SystemInfoSection
import dev.evestaticmapplanner.feature.api.SystemInfoState
import dev.evestaticmapplanner.shared.model.SharedMarkerColor
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CompactInfoField(
    val label: String,
    val value: String,
)

data class CompactSystemInfoPresentation(
    val selectedSystemId: Int,
    val title: String,
    val subtitle: String?,
    val isLoading: Boolean,
    val fields: List<CompactInfoField>,
    val ansiblexConnections: List<String>,
    val jumpOverlayLabels: List<String>,
    val isInJumpIntersection: Boolean,
    val marker: CompactMarkerPresentation?,
    val sharedMarker: CompactSharedMarkerPresentation? = null,
    val extensionSections: List<SystemInfoSection> = emptyList(),
)

data class CompactMarkerPresentation(
    val glyph: String,
    val persistenceLabel: String,
    val name: String?,
    val notes: String?,
    val color: MarkerColor,
)

data class CompactSharedMarkerPresentation(
    val name: String,
    val color: SharedMarkerColor,
    val tags: List<String>,
    val notes: String?,
    val updatedByDisplayName: String,
    val updatedByUserId: String,
    val updatedAtLabel: String,
    val isStale: Boolean,
)

object CompactSystemInfoPresentationBuilder {
    fun build(
        state: MapUiState,
        routeState: RoutePlannerUiState,
        jumpState: JumpOverlayUiState,
        marker: Marker? = null,
        systemInfoState: SystemInfoState = SystemInfoState(null, emptyList()),
        sharedMarkerState: SharedMarkerPresentationState = SharedMarkerPresentationState.Empty,
        localTimeZone: ZoneId = ZoneId.systemDefault(),
    ): CompactSystemInfoPresentation? {
        val selectedSystemId = state.selectedSystemId ?: return null
        val sharedMarker = sharedMarkerState
            .takeIf(SharedMarkerPresentationState::isVisible)
            ?.markersBySystemId
            ?.get(selectedSystemId)
            ?.toCompactPresentation(sharedMarkerState.isStale, localTimeZone)
        val extensionSections = systemInfoState.sections.takeIf { systemInfoState.systemId == selectedSystemId }
            ?: emptyList()
        val fallbackName = state.scene?.nodesById?.get(selectedSystemId)?.system?.name
            ?: "System $selectedSystemId"
        val details = state.selectedSystemDetails?.takeIf { it.system.id == selectedSystemId }
            ?: return CompactSystemInfoPresentation(
                selectedSystemId = selectedSystemId,
                title = fallbackName,
                subtitle = null,
                isLoading = true,
                fields = emptyList(),
                ansiblexConnections = emptyList(),
                jumpOverlayLabels = emptyList(),
                isInJumpIntersection = false,
                marker = marker?.toCompactPresentation(),
                sharedMarker = sharedMarker,
                extensionSections = extensionSections,
            )

        val ansiblex = routeState.ansiblexConnections.filter {
            it.firstSystemId == selectedSystemId || it.secondSystemId == selectedSystemId
        }
        val coveringOverlays = jumpState.coveringOverlays(selectedSystemId)
        return CompactSystemInfoPresentation(
            selectedSystemId = selectedSystemId,
            title = details.system.name,
            subtitle = "${details.region.name} · ${details.constellation.name}",
            isLoading = false,
            fields = listOf(
                CompactInfoField("System ID", details.system.id.toString()),
                CompactInfoField("Security", String.format(Locale.ROOT, "%.6f", details.system.securityStatus)),
                CompactInfoField("Stargates", details.stargateCount.toString()),
                CompactInfoField("Ansiblex", ansiblex.size.toString()),
                CompactInfoField("Jump Coverage", coveringOverlays.size.toString()),
            ),
            ansiblexConnections = ansiblex.take(MAX_ANSIBLEX_DETAILS).map { connection ->
                val other = if (connection.firstSystemId == selectedSystemId) {
                    connection.secondSystemId
                } else {
                    connection.firstSystemId
                }
                "→ $other · ${connection.direction.name}"
            },
            jumpOverlayLabels = coveringOverlays.map { it.label ?: it.id },
            isInJumpIntersection = selectedSystemId in jumpState.intersectionSystemIds,
            marker = marker?.toCompactPresentation(),
            sharedMarker = sharedMarker,
            extensionSections = extensionSections,
        )
    }
}

internal object CompactSystemInfoCardDefaults {
    val alignment: Alignment = Alignment.BottomEnd
    val margin = 16.dp
    val maxWidth = 300.dp
    val maxHeight = 420.dp
    val contentPadding = 12.dp
    const val zIndex = 5f
}

@Composable
fun CompactSystemInfoCard(
    presentation: CompactSystemInfoPresentation,
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xF2182734),
        contentColor = Color(0xFFD7E6F2),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Color(0xFF415466)),
        modifier = modifier
            .widthIn(max = CompactSystemInfoCardDefaults.maxWidth)
            .fillMaxWidth()
            .heightIn(max = CompactSystemInfoCardDefaults.maxHeight)
            .testTag(COMPACT_SYSTEM_INFO_CARD_TEST_TAG)
            .onGloballyPositioned { onBoundsChanged(it.boundsInParent()) },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(CompactSystemInfoCardDefaults.contentPadding),
        ) {
            Text(
                presentation.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            presentation.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFAFC1D1),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (presentation.isLoading) {
                Text("Loading system details…", style = MaterialTheme.typography.bodySmall, color = Color(0xFF91A2B2))
            } else {
                presentation.fields.forEach { field -> CompactInfoRow(field) }
            }
            presentation.marker?.let { marker -> CompactMarkerSection(marker) }
            presentation.sharedMarker?.let { marker -> CompactSharedMarkerSection(marker) }
            if (presentation.isLoading) return@Column
            if (presentation.ansiblexConnections.isNotEmpty()) {
                Text("Ansiblex Connections", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9FB1C1))
                presentation.ansiblexConnections.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (presentation.jumpOverlayLabels.isNotEmpty()) {
                Text("Jump Overlays", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFFD166))
                presentation.jumpOverlayLabels.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD166),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (presentation.isInJumpIntersection) {
                Text(
                    "In selected overlay intersection",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFD166),
                )
            }
            presentation.extensionSections.forEach { section ->
                Text(
                    section.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF9FB1C1),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                section.fields.forEach { field ->
                    CompactInfoRow(CompactInfoField(field.label, field.value))
                }
            }
        }
    }
}

@Composable
private fun CompactSharedMarkerSection(marker: CompactSharedMarkerPresentation) {
    Text("Shared Marker", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9FB1C1))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        SharedMarkerOwnershipBadge(marker.color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                marker.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CompactInfoRow(CompactInfoField("Color", marker.color.name))
            if (marker.tags.isNotEmpty()) {
                Text("Tags", style = MaterialTheme.typography.bodySmall, color = Color(0xFF91A2B2))
                Text(marker.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            marker.notes?.let { notes ->
                Text("Notes", style = MaterialTheme.typography.bodySmall, color = Color(0xFF91A2B2))
                Text(notes, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Updated by ${marker.updatedByDisplayName}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF91A2B2),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(marker.updatedAtLabel, style = MaterialTheme.typography.bodySmall, color = Color(0xFF91A2B2))
            if (marker.isStale) {
                Text(
                    "Shared Map data may be stale",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB86B),
                )
            }
        }
    }
}

@Composable
private fun SharedMarkerOwnershipBadge(color: SharedMarkerColor) {
    val tint = sharedMarkerColor(color)
    Canvas(Modifier.size(24.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.34f
        drawCircle(Color(0xF2111C26), radius, center)
        drawCircle(tint, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        val nodeOffset = radius * 0.34f
        val nodeRadius = radius * 0.2f
        drawLine(
            Color(0xFFF1F5F8),
            Offset(center.x - nodeOffset, center.y + nodeRadius),
            Offset(center.x + nodeOffset, center.y + nodeRadius),
            1.dp.toPx(),
        )
        drawCircle(Color(0xFFF1F5F8), nodeRadius, Offset(center.x - nodeOffset, center.y - nodeRadius * 0.5f))
        drawCircle(Color(0xFFF1F5F8), nodeRadius, Offset(center.x + nodeOffset, center.y - nodeRadius * 0.5f))
    }
}

@Composable
private fun CompactMarkerSection(marker: CompactMarkerPresentation) {
    Text("Marker", style = MaterialTheme.typography.labelMedium, color = Color(0xFF9FB1C1))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(marker.glyph, color = markerColor(marker.color), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                marker.name ?: marker.persistenceLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (marker.name != null) {
                Text(
                    marker.persistenceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF91A2B2),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            marker.notes?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CompactInfoRow(field: CompactInfoField) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            field.label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF91A2B2),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.85f),
        )
        Text(
            field.value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.15f),
        )
    }
}

private const val MAX_ANSIBLEX_DETAILS = 5
internal const val COMPACT_SYSTEM_INFO_CARD_TEST_TAG = "compact-system-info-card"

private fun Marker.toCompactPresentation() = CompactMarkerPresentation(
    glyph = if (persistence == MarkerPersistence.SAVED) "◆" else "◇",
    persistenceLabel = if (persistence == MarkerPersistence.SAVED) "Saved" else "Temporary",
    name = name,
    notes = notes,
    color = color,
)

private fun SharedMarkerPresentation.toCompactPresentation(
    isStale: Boolean,
    localTimeZone: ZoneId,
) = CompactSharedMarkerPresentation(
    name = name,
    color = color,
    tags = tags.map(::sharedMarkerTagLabel),
    notes = notes,
    updatedByDisplayName = updatedByDisplayName,
    updatedByUserId = updatedByUserId,
    updatedAtLabel = SHARED_MARKER_TIME_FORMATTER.format(updatedAt.atZone(localTimeZone)),
    isStale = isStale,
)

private val SHARED_MARKER_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
