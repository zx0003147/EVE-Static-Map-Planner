package dev.evestaticmapplanner.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.evestaticmapplanner.embeddedai.EmbeddedAiController
import dev.evestaticmapplanner.embeddedai.AiCredentialSource
import dev.evestaticmapplanner.embeddedai.AiProviderType
import dev.evestaticmapplanner.embeddedai.PlannerToolRisk
import dev.evestaticmapplanner.ui.EveButton as Button
import dev.evestaticmapplanner.ui.EveTextButton as TextButton
import dev.evestaticmapplanner.ui.EveColors
import dev.evestaticmapplanner.ui.EveOutlinedTextField as OutlinedTextField
import dev.evestaticmapplanner.ui.EveWindowChrome
import dev.evestaticmapplanner.ui.EveWindowSurface

@Composable
fun EmbeddedAiAssistantWindow(
    controller: EmbeddedAiController,
    providerStatus: AiAssistantProviderStatus,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val confirmation by controller.confirmation.collectAsState()
    var prompt by remember { mutableStateOf("Tell me about system 30000142") }

    Window(
        onCloseRequest = {
            controller.cancel()
            onDismiss()
        },
        title = "Embedded AI Assistant",
        state = rememberWindowState(width = 640.dp, height = 460.dp),
    ) {
        EveWindowChrome(window)
        EveWindowSurface(Modifier.fillMaxSize()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text("Embedded AI Assistant", style = MaterialTheme.typography.titleLarge)
                Text(
                    providerStatus.description,
                    color = EveColors.SecondaryText,
                )
                if (!providerStatus.ready) {
                    TextButton(onClick = onOpenSettings) { Text("Open AI Settings") }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Ask the Planner") },
                    enabled = !state.isLoading && providerStatus.ready,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { controller.send(prompt) },
                        enabled = prompt.isNotBlank() && !state.isLoading && providerStatus.ready,
                    ) { Text("Send") }
                    Button(
                        onClick = controller::cancel,
                        enabled = state.isLoading,
                    ) { Text("Cancel") }
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
                        Text(
                            if (confirmation == null) "Waiting for AI provider…" else "Waiting for your confirmation…",
                            color = EveColors.SecondaryText,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .weight(1f)
                        .background(EveColors.InputSurface)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        state.errorMessage != null -> Text(
                            state.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.response.isNotBlank() -> Text(state.response)
                        else -> Text(
                            if (providerStatus.ready) "The answer will appear here." else providerStatus.actionMessage,
                            color = EveColors.SecondaryText,
                        )
                    }
                }
            }
        }
        confirmation?.let { action ->
            AlertDialog(
                onDismissRequest = { controller.denyAction(action.actionId) },
                title = { Text("AI wants to perform an action") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Action: ${action.action}")
                        Text("Target: ${action.target}")
                        Text("Risk: ${action.risk.userFacingLabel()}")
                        action.details.forEach { detail ->
                            Text("${detail.label}: ${detail.value}")
                        }
                        Text("Effect: ${action.effect}", color = EveColors.SecondaryText)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { controller.denyAction(action.actionId) }) { Text("Deny") }
                },
                confirmButton = {
                    Button(onClick = { controller.approveAction(action.actionId) }) { Text("Allow") }
                },
            )
        }
    }
}

private fun PlannerToolRisk.userFacingLabel(): String = when (this) {
    PlannerToolRisk.READ_ONLY -> "Read only"
    PlannerToolRisk.TEMPORARY_UI -> "Temporary map change"
    PlannerToolRisk.PERSISTENT_WRITE -> "Permanent write"
    PlannerToolRisk.DESTRUCTIVE_WRITE -> "Destructive change"
    PlannerToolRisk.EXTERNAL_ACTION -> "External action"
}

data class AiAssistantProviderStatus(
    val providerType: AiProviderType?,
    val modelId: String?,
    val credentialSource: AiCredentialSource?,
) {
    val ready: Boolean get() = providerType != null && credentialSource != null
    val description: String
        get() = when {
            providerType == null -> "AI provider is not configured."
            credentialSource == null -> "Provider: ${providerType.displayName} · AI API Key is not configured."
            else -> "Provider: ${providerType.displayName} · Model: $modelId · Credential: ${credentialSource.displayName}"
        }
    val actionMessage: String
        get() = if (providerType == null) "Configure an AI provider to begin." else "Configure an AI API Key to begin."
}
