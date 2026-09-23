package dev.evestaticmapplanner.ansiblex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.alliance.PublicAllianceMetadataService
import dev.evestaticmapplanner.core.alliance.AllianceDirectorySnapshot
import dev.evestaticmapplanner.core.alliance.AllianceReference
import dev.evestaticmapplanner.core.identity.EveIdentity
import dev.evestaticmapplanner.localization.LocalAppStrings
import dev.evestaticmapplanner.preferences.AnsiblexIdentitySource
import dev.evestaticmapplanner.preferences.AnsiblexPreferences
import dev.evestaticmapplanner.search.AllianceSearchField
import dev.evestaticmapplanner.search.displayLabel
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AnsiblexIdentityDialog(
    esiIdentity: EveIdentity?,
    directorySnapshot: AllianceDirectorySnapshot,
    preferences: AnsiblexPreferences,
    metadataService: PublicAllianceMetadataService,
    onVerifiedAlliance: (AllianceReference) -> Unit,
    onPreferencesChange: (AnsiblexPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val ansiblex = strings.ansiblex
    var manualMode by remember(preferences.identitySource) {
        mutableStateOf(preferences.identitySource == AnsiblexIdentitySource.MANUAL)
    }
    var allianceIdInput by remember { mutableStateOf("") }
    var verificationError by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ansiblex.allianceIdentity) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        manualMode = false
                        onPreferencesChange(preferences.copy(identitySource = AnsiblexIdentitySource.ESI))
                    },
                    selected = !manualMode,
                ) { Text(ansiblex.esiIdentity) }
                if (esiIdentity == null) {
                    Text(ansiblex.esiUnavailable, color = EveColors.Warning)
                } else {
                    Text(esiIdentity.character.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "[${esiIdentity.corporation.ticker}] ${esiIdentity.corporation.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = EveColors.SecondaryText,
                    )
                    Text(
                        esiIdentity.alliance?.let { "[${it.ticker}] ${it.name}" } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = EveColors.SecondaryText,
                    )
                }
                TextButton(onClick = { manualMode = true }, selected = manualMode) {
                    Text(ansiblex.simulateOtherAlliance)
                }
                if (manualMode) {
                    AllianceSearchField(
                        snapshot = directorySnapshot,
                        label = ansiblex.searchAlliance,
                        onSelect = { alliance -> onPreferencesChange(manualIdentityPreferences(alliance)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    preferences.manualAllianceId?.let { id ->
                        val selected = AllianceReference(id, preferences.manualAllianceName, preferences.manualAllianceTicker)
                        Text(
                            "${selected.displayLabel()} · Alliance ID $id",
                            color = EveColors.Important,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            allianceIdInput,
                            {
                                allianceIdInput = it
                                verificationError = false
                            },
                            label = { Text(ansiblex.advancedAllianceId) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                val allianceId = allianceIdInput.trim().toLongOrNull() ?: return@Button
                                verifying = true
                                verificationError = false
                                scope.launch {
                                    val alliance = withContext(Dispatchers.IO) { metadataService.resolve(allianceId) }
                                    verifying = false
                                    if (alliance == null) {
                                        verificationError = true
                                    } else {
                                        onVerifiedAlliance(alliance)
                                        onPreferencesChange(manualIdentityPreferences(alliance))
                                    }
                                }
                            },
                            enabled = !verifying && allianceIdInput.trim().toLongOrNull()?.let { it > 0 } == true,
                        ) { Text(if (verifying) ansiblex.working else ansiblex.verifyAllianceId) }
                    }
                    if (verificationError) Text(ansiblex.verificationFailed, color = EveColors.Error)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(strings.common.close) } },
    )
}

internal fun manualIdentityPreferences(alliance: AllianceReference) = AnsiblexPreferences(
    identitySource = AnsiblexIdentitySource.MANUAL,
    manualAllianceId = alliance.allianceId,
    manualAllianceName = alliance.name,
    manualAllianceTicker = alliance.ticker,
)
