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

/**
 * The device's normal certificate checks, plus one thing web browsers do and Android doesn't: when a
 * server forgets to send the middle ("intermediate") certificate, fetch it from the address written in
 * the server's own certificate and check again. Nothing is trusted that the device wouldn't trust with
 * a complete chain, so self-signed and expired certificates are still refused.
 */
class AiaTrustManager private constructor(private val system: X509TrustManager) : X509TrustManager {
    private val fetched = ConcurrentHashMap<String, List<X509Certificate>>()
    private val factory = CertificateFactory.getInstance("X.509")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (first: CertificateException) {
            val completed = complete(chain) ?: throw first
            try {
                system.checkServerTrusted(completed, authType)
            } catch (_: CertificateException) {
                throw first
            }
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
