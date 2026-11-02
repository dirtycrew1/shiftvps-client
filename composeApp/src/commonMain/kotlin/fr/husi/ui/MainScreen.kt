package fr.husi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import fr.husi.ui.shiftvps.ShiftVpsMainScreen

@Composable
@Suppress("UNUSED_PARAMETER")
fun MainScreen(
    modifier: Modifier = Modifier,
    initialProcessText: String? = null,
) {
    if (LocalViewModelStoreOwner.current != null) {
        ShiftVpsMainScreen(modifier)
    } else {
        CompositionLocalProvider(LocalViewModelStoreOwner provides rememberViewModelStoreOwner()) {
            ShiftVpsMainScreen(modifier)
        }
    }
}
