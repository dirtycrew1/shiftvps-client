package fr.husi.shiftvps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun platformShiftVpsApplicationUpdater(): ShiftVpsApplicationUpdater = object : ShiftVpsApplicationUpdater {
    override val state = MutableStateFlow(ShiftVpsUpdateState()).asStateFlow()
    override fun initialize() = Unit
    override fun onResume() = Unit
    override fun onPause() = Unit
    override fun install() = Unit
    override fun close() = Unit
}
