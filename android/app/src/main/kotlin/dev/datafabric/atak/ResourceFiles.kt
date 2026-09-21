package dev.arachne.atak

import java.io.File
import java.io.InputStream

/** Resource size is not a memory allocation or retention quota. Bound working
 * memory and preserve free disk space while writing a complete temporary copy. */
internal object ResourceFiles {
    private const val RESERVE = 16L * 1024 * 1024

    fun requireSpace(directory: File, bytes: Long) {
        require(bytes >= 0)
        check(bytes <= (directory.usableSpace - RESERVE).coerceAtLeast(0)) { "Insufficient free space for resource bytes." }
    }

    fun copy(input: InputStream, target: File, size: Long? = null, active: () -> Boolean = { true }): Long {
        val directory = checkNotNull(target.parentFile)
        size?.let { require(it >= 0); requireSpace(directory, it) }
        var total = 0L
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(active()) { "Resource operation is no longer active." }
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                require(size == null || total <= size) { "Resource exceeds its declared size." }
                requireSpace(directory, count.toLong())
                output.write(buffer, 0, count)
            }
            require(size == null || total == size) { "Resource is truncated." }
            output.fd.sync()
        }
        return total
    }
}
