package fr.husi.ui.shiftvps

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.husi.resources.Res
import fr.husi.resources.ibm_plex_sans_bold
import fr.husi.resources.ibm_plex_sans_medium
import fr.husi.resources.ibm_plex_sans_regular
import fr.husi.resources.ibm_plex_sans_semibold
import org.jetbrains.compose.resources.Font

internal object ShiftVpsPalette {
    val Background = Color(0xFF08080B)
    val Card = Color(0xFF101115)
    val CardRaised = Color(0xFF17181E)
    val Border = Color(0xFF2B2C34)
    val Text = Color(0xFFEDEDF0)
    val Muted = Color(0xFFA5A7B2)
    val Accent = Color(0xFF9DAAFF)
    val Selected = Color(0xFF1D2240)
    val ButtonTop = Color(0xFF5365D8)
    val ButtonBottom = Color(0xFF4D5FD4)
    val Online = Color(0xFF8ED9B4)
    val Error = Color(0xFFFFB4B9)
}

private val brandColors = darkColorScheme(
    primary = ShiftVpsPalette.Accent,
    onPrimary = ShiftVpsPalette.Background,
    primaryContainer = ShiftVpsPalette.Selected,
    onPrimaryContainer = Color(0xFFE4E7FF),
    background = ShiftVpsPalette.Background,
    onBackground = ShiftVpsPalette.Text,
    surface = ShiftVpsPalette.Card,
    onSurface = ShiftVpsPalette.Text,
    surfaceVariant = ShiftVpsPalette.CardRaised,
    onSurfaceVariant = ShiftVpsPalette.Muted,
    outline = Color(0xFF747682),
    outlineVariant = ShiftVpsPalette.Border,
    error = ShiftVpsPalette.Error,
    errorContainer = Color(0xFF29171E),
    onErrorContainer = Color(0xFFFFDCE0),
)

@Composable
internal fun ShiftVpsTheme(content: @Composable () -> Unit) {
    val family = FontFamily(
        Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(Res.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
        Font(Res.font.ibm_plex_sans_bold, FontWeight.Bold),
    )
    val baseline = Typography()
    val typography = Typography(
        displaySmall = baseline.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
        headlineMedium = baseline.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
        headlineSmall = baseline.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
        titleLarge = baseline.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = baseline.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
        titleSmall = baseline.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
        bodyLarge = baseline.bodyLarge.copy(fontFamily = family, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = baseline.bodyMedium.copy(fontFamily = family, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = baseline.bodySmall.copy(fontFamily = family, fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = baseline.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        labelMedium = baseline.labelMedium.copy(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
        labelSmall = baseline.labelSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    )
    MaterialTheme(colorScheme = brandColors, typography = typography, content = content)
}
