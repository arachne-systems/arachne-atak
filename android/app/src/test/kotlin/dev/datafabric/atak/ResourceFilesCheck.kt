package dev.arachne.atak

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest

/** Run with a 32 MiB heap: the 100 MiB copy cannot be buffered in memory. */
fun main() {
    val directory = Files.createTempDirectory("arachne-resource-files-").toFile()
    val file = File(directory, "resource.bin")
    try {
        val size = 100L * 1024 * 1024 + 123
        val expected = MessageDigest.getInstance("SHA-256")
        val source = object : InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining == 0L) -1 else ((--remaining) % 251).toInt()
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (remaining == 0L) return -1
                val n = minOf(remaining, count.toLong()).toInt()
                repeat(n) { bytes[offset + it] = read().toByte() }
                return n
            }
        }
        check(ResourceFiles.copy(DigestInputStream(source, expected), file, size) == size)
        val actual = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; actual.update(buffer, 0, n) }
        }
        check(actual.digest().contentEquals(expected.digest()))
        check(runCatching { ResourceFiles.copy(byteArrayOf(1, 2).inputStream(), file, 1) }.isFailure)
        check(runCatching { ResourceFiles.copy(byteArrayOf(1).inputStream(), file, 2) }.isFailure)
        check(runCatching { ResourceFiles.copy(byteArrayOf(1).inputStream(), file, -1) }.isFailure)
        check(runCatching { ResourceFiles.requireSpace(directory, Long.MAX_VALUE) }.isFailure)
        check(runCatching { ResourceFiles.copy(byteArrayOf(1).inputStream(), file) { false } }.isFailure)
        check(ResourceFiles.copy(byteArrayOf().inputStream(), file, 0) == 0L)
        println("PASS: 100 MiB+ exact hash with 32 MiB heap; truncation, growth, invalid length, space, cancellation, empty resource")
    } finally { check(file.delete()); check(directory.delete()) }
}
