package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val CyberpunkColorScheme = darkColorScheme(
  primary = NeonCyan,
  secondary = NeonPurple,
  tertiary = NeonPink,
  background = CyberBg,
  surface = CyberSurface,
  onPrimary = CyberBg,
  onSecondary = androidx.compose.ui.graphics.Color.White,
  onTertiary = androidx.compose.ui.graphics.Color.White,
  onBackground = DarkText,
  onSurface = DarkText,
  surfaceVariant = CyberSurfaceVariant,
  onSurfaceVariant = DarkText
)

private val LightColorScheme = darkColorScheme( // Cyberpunk is dark-only!
  primary = NeonCyan,
  secondary = NeonPurple,
  background = CyberBg,
  surface = CyberSurface
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force cyberpunk dark
  dynamicColor: Boolean = false, // Disable dynamic colors to keep neon branding
  content: @Composable () -> Unit,
) {
  val colorScheme = CyberpunkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
