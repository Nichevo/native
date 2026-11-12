package com.nativefinds.app.ui.finds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Find
import com.nativefinds.app.id.Category
import com.nativefinds.app.ui.common.establishmentLabel
import com.nativefinds.app.ui.common.formatDate
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindsScreen(container: AppContainer, onNewFind: () -> Unit, onOpen: (Long) -> Unit, onStats: () -> Unit) {
    val finds by container.db.finds().observeAll().collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My finds") },
                actions = { IconButton(onClick = onStats) { Icon(Icons.Filled.BarChart, "Stats and life list") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewFind,
                icon = { Icon(Icons.Filled.AddAPhoto, null) },
                text = { Text("New find") },
            )
        },
    ) { padding ->
        val list = finds
        when {
            list == null -> {}
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No finds yet. Tap New find, photograph a bird, leaf or flower, and it will be identified and saved here with its location.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> {
                val q = query.trim().lowercase()
                val shown = if (q.isEmpty()) list else list.filter {
                    listOfNotNull(it.commonName, it.scientificName, it.notes).any { s -> s.lowercase().contains(q) }
                }
                LazyColumn(
                    Modifier.padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search ${list.size} finds") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(shown, key = { it.id }) { f -> FindRow(f, onClick = { onOpen(f.id) }) }
                }
            }
        }
    }
}

@Composable
private fun FindRow(f: Find, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = File(f.photoPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.commonName ?: f.scientificName ?: "Unidentified", style = MaterialTheme.typography.titleMedium)
                if (f.commonName != null && f.scientificName != null) {
                    Text(f.scientificName, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                }
                val cat = runCatching { Category.valueOf(f.category).label }.getOrDefault(f.category)
                Text("$cat · ${formatDate(f.createdAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            establishmentLabel(f.establishment)?.let { (t, c) ->
                Text(t, color = c, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
