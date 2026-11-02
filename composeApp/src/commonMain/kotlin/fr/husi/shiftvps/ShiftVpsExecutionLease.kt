package fr.husi.shiftvps

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Shared by the Android UI and :bg process: a VPN session and package replacement cannot overlap. */
internal class ShiftVpsExecutionLease private constructor(
    private val path: String,
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : AutoCloseable {
    private var closed = false

    override fun close() = synchronized(heldPaths) {
        if (closed) return@synchronized
        closed = true
        try {
            lock.release()
        } finally {
            try { file.close() } finally { heldPaths.remove(path) }
        }
    }

    companion object {
        private val heldPaths = mutableSetOf<String>()

        fun tryAcquire(directory: File): ShiftVpsExecutionLease? = synchronized(heldPaths) {
            require(directory.isDirectory || directory.mkdirs())
            val target = File(directory, "shiftvps-execution.lock").canonicalFile
            // Do not reopen a held file in this process: closing another descriptor can release
            // POSIX process-associated locks. Every access to this private file uses this helper.
            if (target.path in heldPaths) return@synchronized null
            val file = RandomAccessFile(target, "rw")
            try {
                val lock = file.channel.tryLock()
                if (lock == null) {
                    file.close()
                    return@synchronized null
                }
                heldPaths.add(target.path)
                ShiftVpsExecutionLease(target.path, file, lock)
            } catch (e: Exception) {
                file.close()
                throw e
            }
        }
    }
}
