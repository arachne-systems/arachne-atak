package dev.arachne.atak

import com.atakmap.comms.CommsProviderFactory
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** One local client/server identity pair shared by a workspace's native
 * listeners. These credentials never authorize a fabric member or peer. */
internal class LocalTakTls {
    val password = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val clientBytes: ByteArray
    val caBytes: ByteArray
    val serverCertificate: X509Certificate
    val clientCertificate: X509Certificate
    val context: SSLContext

    init {
        val provider = CommsProviderFactory.getProvider()
        fun identity() = checkNotNull(provider.generateSelfSignedCert(password))
        fun load(bytes: ByteArray) = KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), password.toCharArray()) }
        fun certificate(keys: KeyStore): X509Certificate {
            val alias = keys.aliases().toList().single { keys.isKeyEntry(it) }
            return (keys.getCertificate(alias) as X509Certificate).also { it.checkValidity() }
        }
        fun trust(cert: X509Certificate) = KeyStore.getInstance("PKCS12").apply {
            load(null, password.toCharArray()); setCertificateEntry("peer", cert)
        }
        val server = load(identity())
        clientBytes = identity()
        serverCertificate = certificate(server)
        clientCertificate = certificate(load(clientBytes))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(server, password.toCharArray()) }
        val trustedClient = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust(clientCertificate)) }
        context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trustedClient.trustManagers, SecureRandom()) }
        caBytes = ByteArrayOutputStream().use { out -> trust(serverCertificate).store(out, password.toCharArray()); out.toByteArray() }
    }
}
