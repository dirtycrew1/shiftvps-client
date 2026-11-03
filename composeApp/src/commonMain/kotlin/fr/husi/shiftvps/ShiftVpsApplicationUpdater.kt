package fr.husi.shiftvps

import kotlinx.coroutines.flow.StateFlow

/** The retained screen owns the Android updater; desktop SDKs own their native update dialogs. */
interface ShiftVpsApplicationUpdater {
    val state: StateFlow<ShiftVpsUpdateState>
    fun initialize()
    fun onResume()
    fun onPause()
    fun check()
    fun install()
    fun close()
}

expect fun platformShiftVpsApplicationUpdater(): ShiftVpsApplicationUpdater
