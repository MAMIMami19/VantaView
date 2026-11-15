// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.security

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.SSLEngine
import java.net.Socket

/**
 * The device's normal certificate checks, plus one thing web browsers do and Android doesn't: when a
 * server forgets to send the middle ("intermediate") certificate, fetch it from the address written in
 * the server's own certificate and check again. Nothing is trusted that the device wouldn't trust with
 * a complete chain, so self-signed and expired certificates are still refused.
 */
class AiaTrustManager private constructor(private val system: X509TrustManager) : X509ExtendedTrustManager() {
    private val fetched = ConcurrentHashMap<String, List<X509Certificate>>()
    private val factory = CertificateFactory.getInstance("X.509")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain, authType, null)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        check(chain, authType, (socket as? javax.net.ssl.SSLSocket)?.handshakeSession?.peerHost)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, authType, engine?.handshakeSession?.peerHost ?: engine?.peerHost)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = system.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = system.checkClientTrusted(chain, authType)

    private fun check(chain: Array<X509Certificate>, authType: String, host: String?) {
        // A certificate the user chose to trust (exact match only; the name is still checked by the HTTP client).
        if (chain.isNotEmpty() && CertPins.isPinned(chain[0])) return
        try {
            system.checkServerTrusted(chain, authType)
        } catch (first: CertificateException) {
            val completed = complete(chain)
            if (completed != null) {
                try {
                    system.checkServerTrusted(completed, authType)
                    return
                } catch (_: CertificateException) { }
            }
            if (chain.isNotEmpty()) CertPins.recordRejected(host, chain[0])
            throw first
        }
    }

    /** The chain with missing intermediates added (up to three), or null if none could be found. */
    private fun complete(chain: Array<X509Certificate>): Array<X509Certificate>? {
        if (chain.isEmpty()) return null
        val out = chain.toMutableList()
        repeat(3) {
            val last = out.last()
            if (last.subjectX500Principal == last.issuerX500Principal) return@repeat
            val url = caIssuersUrl(last) ?: return if (out.size > chain.size) out.toTypedArray() else null
            val certs = fetched[url] ?: download(url)?.also { fetched[url] = it } ?: return if (out.size > chain.size) out.toTypedArray() else null
            val issuer = certs.firstOrNull { it.subjectX500Principal == last.issuerX500Principal } ?: return if (out.size > chain.size) out.toTypedArray() else null
            out += issuer
        }
        return if (out.size > chain.size) out.toTypedArray() else null
    }

    private fun download(url: String): List<X509Certificate>? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.inputStream.use { input ->
            val bytes = input.readBytes()
            factory.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val AIA_OID = "1.3.6.1.5.5.7.1.1"

        /** The "CA Issuers" address in a certificate's Authority Information Access extension. */
        fun caIssuersUrl(cert: X509Certificate): String? {
            val raw = cert.getExtensionValue(AIA_OID) ?: return null
            val text = String(raw, Charsets.ISO_8859_1)
            return Regex("""https?://[\x21-\x7e]+?\.(?:crt|cer|der|p7c)""", RegexOption.IGNORE_CASE).find(text)?.value
                ?: Regex("""http://[\x21-\x7e]+""").findAll(text).map { it.value }.firstOrNull { !it.contains("ocsp", true) }
        }

        /** The device's trust manager wrapped with intermediate fetching, and a socket factory using it. */
        fun create(): Pair<SSLSocketFactory, X509TrustManager> {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val tm = AiaTrustManager(system)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), null)
            return ctx.socketFactory to tm
        }
    }
}
