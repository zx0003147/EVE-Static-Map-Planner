package dev.evestaticmapplanner.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.evestaticmapplanner.core.model.SolarSystem

@Composable
fun SystemSearchField(
    value: String,
    label: String,
    results: List<SolarSystem>,
    onValueChange: (String) -> Unit,
    onSelect: (SolarSystem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        results.take(6).forEach { system ->
            TextButton(onClick = { onSelect(system) }, modifier = Modifier.fillMaxWidth()) {
                Text("${system.name}  ·  ${system.id}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
