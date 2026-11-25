package wtf.evi.mobile.ui.theme

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wtf.evi.mobile.data.model.AccentColor
import wtf.evi.mobile.data.model.AppSettings
import wtf.evi.mobile.data.model.AppTheme

data class EviColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val accent: Color,
    val onAccent: Color,
    val coverTint: Color,
    val textPrimary: Color = Color(0xFFF5F5F7),
    val textSecondary: Color = Color(0xFFA1A1AA),
    val textMuted: Color = Color(0xFF71717A),
    val border: Color = Color(0x1FFFFFFF)
)

val LocalEviColors = staticCompositionLocalOf {
    EviColors(
        background = Color(0xFF09090B),
        surface = Color(0xFF121216),
        surfaceElevated = Color(0xFF1B1B22),
        accent = Color(0xFF1ED760),
        onAccent = Color.Black,
        coverTint = Color(0xFF1ED760)
    )
}

@Composable
fun rememberCoverColor(imageUrl: String?): Color {
    val context = LocalContext.current
    var extracted by remember(imageUrl) { mutableStateOf(Color(0xFF1ED760)) }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) {
            extracted = Color(0xFF1ED760)
            return@LaunchedEffect
        }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val res = Coil.imageLoader(context).execute(req)
                if (res is SuccessResult) {
                    val bmp = (res.drawable as? BitmapDrawable)?.bitmap
                    if (bmp != null) {
                        val palette = Palette.from(bmp).generate()
                        val argb = palette.getVibrantColor(
                            palette.getLightVibrantColor(
                                palette.getDominantColor(0xFF1ED760.toInt())
                            )
                        )
                        ensureReadableAccent(Color(argb))
                    } else null
                } else null
            }.getOrNull()
        }
        if (color != null) {
            extracted = color
        }
    }

    val animated by animateColorAsState(
        targetValue = extracted,
        animationSpec = tween(durationMillis = 450),
        label = "coverAccent"
    )
    return animated
}

private fun ensureReadableAccent(raw: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (raw.red * 255).toInt(),
        (raw.green * 255).toInt(),
        (raw.blue * 255).toInt(),
        hsv
    )
    if (hsv[2] < 0.55f) hsv[2] = 0.72f
    if (hsv[1] in 0.05f..0.35f) hsv[1] = 0.45f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private val EviTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
)

@Composable
fun EviTheme(
    settings: AppSettings,
    currentCoverUrl: String?,
    content: @Composable () -> Unit
) {
    val coverColor = rememberCoverColor(currentCoverUrl)
    val theme: AppTheme = settings.theme
    val rawAccent = if (settings.accent == AccentColor.COVER) coverColor else Color(settings.accent.colorHex)
    val accent by animateColorAsState(targetValue = rawAccent, animationSpec = tween(300), label = "accent")
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF09090B) else Color.White

    val eviColors = EviColors(
        background = Color(theme.bgHex),
        surface = Color(theme.surfaceHex),
        surfaceElevated = Color(theme.surfaceElevatedHex),
        accent = accent,
        onAccent = onAccent,
        coverTint = coverColor
    )

    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        background = eviColors.background,
        onBackground = eviColors.textPrimary,
        surface = eviColors.surface,
        onSurface = eviColors.textPrimary,
        surfaceVariant = eviColors.surfaceElevated,
        onSurfaceVariant = eviColors.textSecondary,
        outline = eviColors.border
    )

    CompositionLocalProvider(LocalEviColors provides eviColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EviTypography,
            content = content
        )
    }
}
