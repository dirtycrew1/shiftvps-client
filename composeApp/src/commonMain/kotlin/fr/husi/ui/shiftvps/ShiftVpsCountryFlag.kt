package fr.husi.ui.shiftvps

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.husi.resources.*
import org.jetbrains.compose.resources.painterResource

/** Country comes only from authoritative server metadata; names are not parsed. */
@Composable
internal fun ShiftVpsCountryFlag(countryCode: String, modifier: Modifier = Modifier) {
    val resource = when (countryCode) {
        "NL" -> Res.drawable.flag_nl
        "LT" -> Res.drawable.flag_lt
        "LV" -> Res.drawable.flag_lv
        "IL" -> Res.drawable.flag_il
        "SE" -> Res.drawable.flag_se
        "FI" -> Res.drawable.flag_fi
        "DE" -> Res.drawable.flag_de
        "EE" -> Res.drawable.flag_ee
        "KZ" -> Res.drawable.flag_kz
        else -> null
    }
    if (resource == null) {
        Icon(painterResource(Res.drawable.public_icon), contentDescription = null,
            tint = ShiftVpsPalette.Muted, modifier = modifier.size(28.dp, 21.dp))
    } else {
        val shape = RoundedCornerShape(3.dp)
        Image(painterResource(resource), contentDescription = null,
            modifier = modifier.size(28.dp, 21.dp).clip(shape).border(0.5.dp, Color.White.copy(alpha = 0.16f), shape))
    }
}
