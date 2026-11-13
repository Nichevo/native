package com.nativefinds.app.ui.map

import android.graphics.Color as AColor
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Find
import com.nativefinds.app.info.Sighting
import com.nativefinds.app.ui.stats.Hotspot
import com.nativefinds.app.ui.stats.hotspots
import com.nativefinds.app.ui.stats.yearOf
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Calendar

/** Markers we add, tagged so each layer can be cleared on its own. */
private class FindMarker(map: MapView) : Marker(map)
private class SightingMarker(map: MapView) : Marker(map)
private class HotspotCircle : Polygon()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(container: AppContainer, onOpen: (Long) -> Unit) {
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState(initial = null)
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    var onlyThisYear by rememberSaveable { mutableStateOf(false) }
    var showHotspots by rememberSaveable { mutableStateOf(false) }
    var showSightings by rememberSaveable { mutableStateOf(false) }
    var sightings by remember { mutableStateOf<List<Sighting>>(emptyList()) }
    var sightingsMsg by remember { mutableStateOf<String?>(null) }
    var loadingSightings by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }

    val located = finds.filter { it.latitude != null && it.longitude != null }
        .filter { !onlyThisYear || yearOf(it.createdAt) == thisYear }
    val spots = remember(located) { hotspots(located) }
    val ctx = LocalContext.current
    val open by rememberUpdatedState(onOpen)
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(5.5)
            controller.setCenter(GeoPoint(-41.0, 174.0)) // New Zealand until there are finds
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }

    LaunchedEffect(showSightings) {
        if (!showSightings) { sightings = emptyList(); sightingsMsg = null; return@LaunchedEffect }
        val s = settings
        if (s?.offlineMode == true) { sightingsMsg = "Sightings need the internet (offline mode is on)."; return@LaunchedEffect }
        loadingSightings = true
        val loc = container.location.current()
        val centre = loc?.let { GeoPoint(it.latitude, it.longitude) } ?: map.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
        runCatching {
            container.inat.recentSightings(centre.latitude, centre.longitude, 10, 30, s?.regionPlaceId ?: 6803)
        }.onSuccess {
            sightings = it
            sightingsMsg = "${it.size} sightings by others within 10 km in the last 30 days (orange)."
            map.controller.setZoom(12.5)
            map.controller.setCenter(centre)
        }.onFailure { sightingsMsg = "Couldn't load sightings: ${it.message}" }
        loadingSightings = false
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Map · ${located.size} finds") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = onlyThisYear, onClick = { onlyThisYear = !onlyThisYear }, label = { Text("$thisYear only") })
                FilterChip(selected = showHotspots, onClick = { showHotspots = !showHotspots }, label = { Text("Hotspots") })
                FilterChip(selected = showSightings, onClick = { showSightings = !showSightings }, label = { Text("Seen nearby") })
            }
            if (loadingSightings) LinearProgressIndicator(Modifier.fillMaxWidth())
            sightingsMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            }
            AndroidView(
                factory = { map },
                // osmdroid draws tiles outside its own bounds unless clipped, covering the bars and chips
                modifier = Modifier.fillMaxSize().clipToBounds(),
                update = { mv ->
                    showHotspotLayer(mv, if (showHotspots) spots else emptyList())
                    showSightingLayer(mv, sightings)
                    showFinds(mv, located, fit = !fitted && !showSightings) { open(it) }
                    if (located.isNotEmpty()) fitted = true
                },
            )
        }
    }
}

private fun showFinds(map: MapView, finds: List<Find>, fit: Boolean, onOpen: (Long) -> Unit) {
    map.overlays.removeAll { it is FindMarker }
    finds.forEach { f ->
        val m = FindMarker(map).apply {
            position = GeoPoint(f.latitude!!, f.longitude!!)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = f.commonName ?: f.scientificName ?: "Unidentified"
            snippet = f.scientificName
            setOnMarkerClickListener { marker, _ ->
                if (marker.isInfoWindowShown) onOpen(f.id) else marker.showInfoWindow()
                true
            }
        }
        map.overlays.add(m)
    }
    if (fit) {
        if (finds.size == 1) {
            map.controller.setZoom(14.0)
            map.controller.setCenter(GeoPoint(finds[0].latitude!!, finds[0].longitude!!))
        } else if (finds.size > 1) {
            val box = BoundingBox.fromGeoPoints(finds.map { GeoPoint(it.latitude!!, it.longitude!!) })
            map.post { map.zoomToBoundingBox(box.increaseByScale(1.3f), false, 64) }
        }
    }
    map.invalidate()
}

private fun showSightingLayer(map: MapView, sightings: List<Sighting>) {
    map.overlays.removeAll { it is SightingMarker }
    sightings.forEach { s ->
        val m = SightingMarker(map).apply {
            position = GeoPoint(s.latitude, s.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = icon?.mutate()?.also { it.colorFilter = PorterDuffColorFilter(AColor.rgb(239, 108, 0), PorterDuff.Mode.SRC_ATOP) }
            alpha = 0.85f
            title = s.commonName ?: s.scientificName
            snippet = listOfNotNull(s.scientificName.takeIf { s.commonName != null }, s.observedOn?.let { "seen $it" }).joinToString(" · ")
        }
        // Keep sightings under the user's own finds.
        map.overlays.add(0, m)
    }
    map.invalidate()
}

private fun showHotspotLayer(map: MapView, spots: List<Hotspot>) {
    map.overlays.removeAll { it is HotspotCircle }
    val max = spots.maxOfOrNull { it.species }?.coerceAtLeast(1) ?: 1
    spots.forEach { h ->
        val radius = 150.0 + 450.0 * h.species / max
        val c = HotspotCircle().apply {
            setPoints(Polygon.pointsAsCircle(GeoPoint(h.lat, h.lng), radius))
            fillPaint.color = AColor.argb(70, 46, 125, 50)
            outlinePaint.color = AColor.argb(200, 27, 94, 32)
            outlinePaint.strokeWidth = 3f
            title = "${h.species} species · ${h.finds} finds"
            snippet = h.topNames.joinToString(", ")
        }
        map.overlays.add(0, c)
    }
    map.invalidate()
}
