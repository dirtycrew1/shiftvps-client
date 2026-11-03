package fr.husi.shiftvps

import fr.husi.platform.PlatformInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun platformShiftVpsApplicationUpdater(): ShiftVpsApplicationUpdater = object : ShiftVpsApplicationUpdater {
    private val mutableState = MutableStateFlow(ShiftVpsUpdateState())
    override val state = mutableState.asStateFlow()
    override fun initialize() = Unit
    override fun onResume() = Unit
    override fun onPause() = Unit
    override fun check() {
        val opened = runCatching {
            when {
                PlatformInfo.isWindows -> DesktopWindowsUpdater.check()
                PlatformInfo.isMacOs -> DesktopMacUpdater.check()
                else -> false
            }
        }.getOrDefault(false)
        mutableState.value = ShiftVpsUpdateState(checkPhase = if (opened) {
            ShiftVpsUpdateCheckPhase.ExternalDialog
        } else ShiftVpsUpdateCheckPhase.Failed)
    }
    override fun install() = check()
    override fun close() = Unit
}
