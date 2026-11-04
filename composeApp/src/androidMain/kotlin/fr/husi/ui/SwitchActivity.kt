package fr.husi.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import fr.husi.lib.R
import fr.husi.repository.resolveRepository
import fr.husi.shiftvps.managedQuickServerChoices
import fr.husi.shiftvps.prepareManagedQuickConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Opened directly by the notification; no VPN is changed until a location is selected. */
class SwitchActivity : PrivacyModeActivity() {
    private var dialog: AlertDialog? = null
    private var selecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val choices = try {
                managedQuickServerChoices()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            if (choices.isEmpty()) {
                openMainScreen()
                return@launch
            }
            dialog = AlertDialog.Builder(this@SwitchActivity)
                .setTitle(R.string.shiftvps_switch_server)
                .setSingleChoiceItems(choices.map { it.name }.toTypedArray(), choices.indexOfFirst { it.selected }) { _, index ->
                    if (selecting) return@setSingleChoiceItems
                    val choice = choices[index]
                    if (choice.selected) {
                        finish()
                        return@setSingleChoiceItems
                    }
                    selecting = true
                    dialog?.listView?.isEnabled = false
                    dialog?.setCancelable(false)
                    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                    lifecycleScope.launch {
                        if (prepareManagedQuickConnection(choice.profileId)) {
                            // A stopped service has no reload receiver, so this never connects by itself.
                            resolveRepository().reloadServiceIfRunning()
                            finish()
                        } else openMainScreen()
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create()
            dialog?.show()
        }
    }

    private fun openMainScreen() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }
}
