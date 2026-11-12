package com.nativefinds.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF2E5E3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E4BE),
    onPrimaryContainer = Color(0xFF00210B),
    secondary = Color(0xFF52634F),
    tertiary = Color(0xFF39656D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9BD3A3),
    onPrimary = Color(0xFF003916),
    primaryContainer = Color(0xFF155226),
    onPrimaryContainer = Color(0xFFB7E4BE),
    secondary = Color(0xFFB9CCB4),
    tertiary = Color(0xFFA1CED7),
)

@Composable
fun NativeFindsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
