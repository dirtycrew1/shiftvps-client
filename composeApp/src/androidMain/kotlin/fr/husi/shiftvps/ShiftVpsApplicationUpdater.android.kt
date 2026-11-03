package fr.husi.shiftvps

import fr.husi.repository.resolveAndroidRepository

actual fun platformShiftVpsApplicationUpdater(): ShiftVpsApplicationUpdater {
    val updater = ShiftVpsAndroidUpdater(resolveAndroidRepository().context)
    return object : ShiftVpsApplicationUpdater {
        override val state = updater.state
        override fun initialize() = updater.initialize()
        override fun onResume() = updater.onResume()
        override fun onPause() = updater.onPause()
        override fun check() = updater.check()
        override fun install() = updater.install()
        override fun close() = updater.close()
    }
}
