package com.nativefinds.app.ui.places

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Place
import kotlinx.coroutines.launch

val PLACE_SIZES = listOf(25f, 50f, 100f, 250f, 1000f)

fun sizeLabel(m: Float): String = if (m >= 1000f) "${(m / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} km" else "${m.toInt()} m"

/** "My places" section for Settings: list saved places and add one where you are standing. */
@Composable
fun PlacesSection(container: AppContainer, onOpenPlace: (Long) -> Unit = {}) {
    val places by container.db.places().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Place?>(null) }

    Text("My places", style = MaterialTheme.typography.titleSmall)
    Text(
        "Save spots like \"My garden\". Every find inside a place is grouped there automatically, " +
            "so you can filter your finds by place and see its species list. Each find keeps its own date and location.",
        style = MaterialTheme.typography.bodySmall,
    )
    places.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                Text("Within ${sizeLabel(p.radiusMeters)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            TextButton(onClick = { onOpenPlace(p.id) }) { Text("Open") }
            TextButton(onClick = { editing = p }) { Text("Edit") }
            IconButton(onClick = { scope.launch { container.db.places().delete(p) } }) { Icon(Icons.Filled.Delete, "Remove ${p.name}") }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = {
        scope.launch {
            status = "Finding where you are…"
            val loc = runCatching { container.location.current() }.getOrNull()
            if (loc == null) status = "Couldn't get your location. Check location is on and allowed for Native Finds."
            else { status = null; adding = loc.latitude to loc.longitude }
        }
    }) { Text("Add a place where I am now") }

    adding?.let { (lat, lng) ->
        PlaceDialog(
            title = "New place",
            initialName = if (places.isEmpty()) "My garden" else "",
            initialRadius = 50f,
            onDismiss = { adding = null },
        ) { name, radius ->
            scope.launch {
                container.db.places().insert(Place(name = name, latitude = lat, longitude = lng, radiusMeters = radius, createdAt = System.currentTimeMillis()))
            }
            adding = null
        }
    }
    editing?.let { p ->
        PlaceDialog(title = "Edit place", initialName = p.name, initialRadius = p.radiusMeters, onDismiss = { editing = null }) { name, radius ->
            scope.launch { container.db.places().update(p.copy(name = name, radiusMeters = radius)) }
            editing = null
        }
    }
}

/** Name and size picker for a place. */
@Composable
fun PlaceDialog(
    title: String,
    initialName: String,
    initialRadius: Float,
    onDismiss: () -> Unit,
    onSave: (String, Float) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var radius by remember { mutableStateOf(initialRadius) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name, e.g. My garden") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Size (distance from this spot)", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PLACE_SIZES.forEach { m -> FilterChip(selected = radius == m, onClick = { radius = m }, label = { Text(sizeLabel(m)) }) }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), radius) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
