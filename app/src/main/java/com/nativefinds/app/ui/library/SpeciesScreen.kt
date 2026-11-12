package com.nativefinds.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.SpeciesInfo
import com.nativefinds.app.ui.common.PestBanner
import com.nativefinds.app.ui.common.SpeciesInfoCard
import com.nativefinds.app.ui.common.formatDate
import java.io.File

/** A species from the library: its details, and every find of it you've saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeciesScreen(container: AppContainer, scientificName: String, onBack: () -> Unit, onOpenFind: (Long) -> Unit) {
    val settings by container.settings.settings.collectAsState(initial = null)
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())
    var info by remember { mutableStateOf<SpeciesInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(scientificName, settings?.regionPlaceId) {
        val s = settings ?: return@LaunchedEffect
        loading = true
        info = container.species.info(scientificName, s.regionPlaceId, s.offlineMode)
        loading = false
    }
    val key = speciesKey(scientificName)
    val mine = finds.filter { f ->
        (info?.taxonId != null && f.taxonId == info?.taxonId) || f.scientificName?.let(::speciesKey) == key
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.commonName ?: scientificName) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            settings?.let { s -> container.pests.lookup(scientificName, s.regionPlaceId)?.let { PestBanner(it) } }
            SpeciesInfoCard(info, scientificName, settings?.regionName ?: "", loading)
            if (mine.isEmpty()) {
                Text("Not found yet.", style = MaterialTheme.typography.titleSmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32))
                    Text("  Your finds (${mine.size})", style = MaterialTheme.typography.titleSmall)
                }
                mine.forEach { f ->
                    Card(onClick = { onOpenFind(f.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = File(f.photoPath),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Text("  " + formatDate(f.createdAt))
                        }
                    }
                }
            }
        }
    }
}
