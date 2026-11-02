package fr.husi.shiftvps

import fr.husi.bg.ServiceState

enum class ShiftVpsUpdatePhase {
    Idle, Available, Downloading, NeedsPermission, NeedsDisconnect, Installing, Failed,
}

data class ShiftVpsUpdateState(
    val phase: ShiftVpsUpdatePhase = ShiftVpsUpdatePhase.Idle,
    val availableVersion: String? = null,
    val progressPercent: Int = 0,
    val message: String? = null,
)

internal fun canRestartApplication(state: ServiceState): Boolean =
    state == ServiceState.Idle || state == ServiceState.Stopped
