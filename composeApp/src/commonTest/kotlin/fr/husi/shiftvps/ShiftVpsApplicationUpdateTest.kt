package fr.husi.shiftvps

import fr.husi.bg.ServiceState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShiftVpsApplicationUpdateTest {
    @Test
    fun `checking stays separate from a verified install offer`() {
        val offer = ShiftVpsUpdateState(ShiftVpsUpdatePhase.Available, "0.1.10")
        assertTrue(canCheckApplicationUpdate(offer))
        assertFalse(canCheckApplicationUpdate(offer.copy(checkPhase = ShiftVpsUpdateCheckPhase.Checking)))
        assertTrue(canCheckApplicationUpdate(offer.copy(checkPhase = ShiftVpsUpdateCheckPhase.Failed)))
    }

    @Test
    fun `a check cannot interrupt download permission disconnect or installation`() {
        for (phase in listOf(ShiftVpsUpdatePhase.Downloading, ShiftVpsUpdatePhase.NeedsPermission,
            ShiftVpsUpdatePhase.NeedsDisconnect, ShiftVpsUpdatePhase.Installing)) {
            assertFalse(canCheckApplicationUpdate(ShiftVpsUpdateState(phase)), phase.name)
        }
        for (phase in listOf(ShiftVpsUpdatePhase.Idle, ShiftVpsUpdatePhase.Available, ShiftVpsUpdatePhase.Failed)) {
            assertTrue(canCheckApplicationUpdate(ShiftVpsUpdateState(phase)), phase.name)
        }
    }

    @Test
    fun `checking does not weaken the explicit disconnect requirement for installation`() {
        for (state in ServiceState.entries) {
            if (state == ServiceState.Idle || state == ServiceState.Stopped) assertTrue(canRestartApplication(state))
            else assertFalse(canRestartApplication(state), state.name)
        }
    }
}
