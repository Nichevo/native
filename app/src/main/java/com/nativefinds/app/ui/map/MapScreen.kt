package com.nativefinds.app.ui.map

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.nativefinds.app.AppContainer
import com.nativefinds.app.data.Find
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(container: AppContainer, onOpen: (Long) -> Unit) {
    val finds by container.db.finds().observeAll().collectAsState(initial = emptyList())
    val located = finds.filter { it.latitude != null && it.longitude != null }
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

    Scaffold(topBar = { TopAppBar(title = { Text("Map · ${located.size} finds") }) }) { padding ->
        AndroidView(
            factory = { map },
            modifier = Modifier.padding(padding).fillMaxSize(),
            update = { mv -> showFinds(mv, located) { open(it) } },
        )
    }
}

private fun showFinds(map: MapView, finds: List<Find>, onOpen: (Long) -> Unit) {
    map.overlays.removeAll { it is Marker }
    finds.forEach { f ->
        val m = Marker(map).apply {
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
    if (finds.size == 1) {
        map.controller.setZoom(14.0)
        map.controller.setCenter(GeoPoint(finds[0].latitude!!, finds[0].longitude!!))
    } else if (finds.size > 1) {
        val box = BoundingBox.fromGeoPoints(finds.map { GeoPoint(it.latitude!!, it.longitude!!) })
        map.post { map.zoomToBoundingBox(box.increaseByScale(1.3f), false, 64) }
    }
    map.invalidate()
}
