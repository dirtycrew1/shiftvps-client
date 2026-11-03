package fr.husi.shiftvps

import fr.husi.bg.ServiceState

enum class ShiftVpsUpdatePhase {
    Idle, Available, Downloading, NeedsPermission, NeedsDisconnect, Installing, Failed,
}

enum class ShiftVpsUpdateCheckPhase {
    Idle, Checking, UpToDate, Failed, ExternalDialog,
}

data class ShiftVpsUpdateState(
    val phase: ShiftVpsUpdatePhase = ShiftVpsUpdatePhase.Idle,
    val availableVersion: String? = null,
    val progressPercent: Int = 0,
    val message: String? = null,
    val checkPhase: ShiftVpsUpdateCheckPhase = ShiftVpsUpdateCheckPhase.Idle,
)

internal fun canCheckApplicationUpdate(state: ShiftVpsUpdateState): Boolean =
    state.checkPhase != ShiftVpsUpdateCheckPhase.Checking && when (state.phase) {
        ShiftVpsUpdatePhase.Downloading, ShiftVpsUpdatePhase.Installing,
        ShiftVpsUpdatePhase.NeedsPermission, ShiftVpsUpdatePhase.NeedsDisconnect -> false
        else -> true
    }

internal fun canRestartApplication(state: ServiceState): Boolean =
    state == ServiceState.Idle || state == ServiceState.Stopped
