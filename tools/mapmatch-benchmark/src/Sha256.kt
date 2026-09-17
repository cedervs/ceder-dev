package worlddiscovery.benchmark

import java.io.File
import java.security.MessageDigest

/** Plain content hashing for [EngineConfigIdentity] -- no cryptographic-signing infrastructure,
 * per protocol correction §4 ("do not require cryptographic infrastructure if simple SHA-256/path
 * metadata is enough"). */
object Sha256 {
    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun ofText(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
