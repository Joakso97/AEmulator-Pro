package app.aemu.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme


private val Light = lightColorScheme(
    primary = Color(0xFF0061A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF), onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7), onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DAFF), onTertiaryContainer = Color(0xFF251431),
    background = Color(0xFFF8F9FF), surface = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFECEDF4), surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E8), surfaceContainerLow = Color(0xFFF2F3F9),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9ECAFF), onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D), onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB), onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4758), onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4), onTertiary = Color(0xFF3B2947),
    tertiaryContainer = Color(0xFF533F5F), onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF111318), surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A), surfaceContainerLow = Color(0xFF191C20),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()
private val AppType = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private fun hsl(h: Float, s: Float, l: Float) =
    Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

private fun onColor(c: Color) = if (c.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White


fun schemeFromSeed(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.25f, 1f)
    val ns = (s * 0.3f).coerceAtMost(0.16f) 
    return if (!dark) {
        val primary = seed
        lightColorScheme(
            primary = primary, onPrimary = onColor(primary),
            primaryContainer = hsl(h, s, 0.90f), onPrimaryContainer = hsl(h, s, 0.12f),
            secondary = hsl(h, s * 0.28f, 0.38f), onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.45f, 0.90f), onSecondaryContainer = hsl(h, s * 0.4f, 0.12f),
            tertiary = hsl(h + 60f, s * 0.3f, 0.38f), onTertiary = Color.White,
            tertiaryContainer = hsl(h + 60f, s * 0.5f, 0.88f), onTertiaryContainer = hsl(h + 60f, s * 0.4f, 0.12f),
            background = hsl(h, ns, 0.98f), surface = hsl(h, ns, 0.98f),
            surfaceContainerLow = hsl(h, ns, 0.96f), surfaceContainer = hsl(h, ns, 0.94f),
            surfaceContainerHigh = hsl(h, ns, 0.92f), surfaceContainerHighest = hsl(h, ns, 0.90f),
            error = Color(0xFFBA1A1A),
        )
    } else {
        val primary = hsl(h, s, 0.78f)
        darkColorScheme(
            primary = primary, onPrimary = hsl(h, s, 0.16f),
            primaryContainer = hsl(h, s, 0.26f), onPrimaryContainer = hsl(h, s, 0.90f),
            secondary = hsl(h, s * 0.4f, 0.80f), onSecondary = hsl(h, s * 0.3f, 0.16f),
            secondaryContainer = hsl(h, s * 0.3f, 0.26f), onSecondaryContainer = hsl(h, s * 0.45f, 0.90f),
            tertiary = hsl(h + 60f, s * 0.4f, 0.78f), onTertiary = hsl(h + 60f, s * 0.3f, 0.16f),
            tertiaryContainer = hsl(h + 60f, s * 0.3f, 0.26f), onTertiaryContainer = hsl(h + 60f, s * 0.5f, 0.88f),
            background = hsl(h, ns, 0.08f), surface = hsl(h, ns, 0.08f),
            surfaceContainerLow = hsl(h, ns, 0.10f), surfaceContainer = hsl(h, ns, 0.13f),
            surfaceContainerHigh = hsl(h, ns, 0.17f), surfaceContainerHighest = hsl(h, ns, 0.21f),
        )
    }
}

@Composable
fun AemuTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    
    val rev = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(ctx) {
        val sp = app.aemu.AppPrefs.prefs(ctx)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> rev.intValue++ }
        sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    rev.intValue
    val dark = forceDark ?: when (app.aemu.AppPrefs.theme(ctx)) {
        app.aemu.AppPrefs.THEME_LIGHT -> false
        app.aemu.AppPrefs.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val accent = app.aemu.AppPrefs.accentColor(ctx)
    val scheme = when {
        accent != 0 -> schemeFromSeed(Color(accent), dark)
        Build.VERSION.SDK_INT >= 31 && app.aemu.AppPrefs.dynamicColor(ctx) -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = AppType,
        content = {
            SystemBarAppearance()
            content()
        },
    )
}

@Composable
internal fun SystemBarAppearance() {
    val view = LocalView.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SideEffect {
        val window = findWindow(view)
        window?.let { w ->
            @Suppress("DEPRECATION")
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(w, w.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }
}

private fun findWindow(view: android.view.View): android.view.Window? {
    var p: android.view.ViewParent? = view.parent
    while (p != null) {
        if (p is androidx.compose.ui.window.DialogWindowProvider) {
            return p.window
        }
        p = p.parent
    }
    var context = view.context
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) {
            return context.window
        }
        context = context.baseContext
    }
    return null
}

val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
