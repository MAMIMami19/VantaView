// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.security

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A server certificate the device refused, as shown when asking whether to trust it. */
data class UntrustedCert(
    val host: String,
    val subject: String,
    val issuer: String,
    val validUntil: String,
    val expired: Boolean,
    val selfSigned: Boolean,
    val fingerprint: String,
)

/**
 * Server certificates the user chose to trust anyway, like a web browser's "Proceed": each is trusted
 * by its exact SHA-256 fingerprint only, so a different (possibly fake) certificate is still refused,
 * and the server's name must still match it. Also remembers the last refused certificate per server.
 */
object CertPins {
    private var sp: SharedPreferences? = null
    private val pins = ConcurrentHashMap.newKeySet<String>()
    private val rejected = ConcurrentHashMap<String, X509Certificate>()

    fun init(context: Context) {
        sp = context.getSharedPreferences("cert_pins", Context.MODE_PRIVATE).also { p ->
            pins.addAll(p.getStringSet("pins", emptySet()).orEmpty())
        }
    }

    fun fingerprint(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

    fun isPinned(c: X509Certificate) = fingerprint(c) in pins

    fun pin(fingerprint: String) {
        pins += fingerprint
        // Forget the refusal, so a later failure is reported as what it really is.
        rejected.entries.removeIf { fingerprint(it.value) == fingerprint }
        sp?.edit()?.putStringSet("pins", pins.toSet())?.apply()
    }

    fun clearAll() { pins.clear(); sp?.edit()?.remove("pins")?.apply() }

    val count: Int get() = pins.size

    fun isPinned(fingerprint: String) = fingerprint in pins

    internal fun recordRejected(host: String?, c: X509Certificate) { if (host != null) rejected[host.lowercase()] = c }

    /**
     * The usual check that a certificate belongs to the server's name, except that a certificate the
     * user chose to trust is accepted for the server it was trusted on even when its name differs (as a
     * browser's "Proceed" does). A mismatch on any other certificate is remembered so it can be trusted.
     */
    fun hostnameVerifier(default: javax.net.ssl.HostnameVerifier): javax.net.ssl.HostnameVerifier =
        javax.net.ssl.HostnameVerifier { host, session ->
            if (default.verify(host, session)) return@HostnameVerifier true
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return@HostnameVerifier false
            if (isPinned(leaf)) true else { recordRejected(host, leaf); false }
        }

    /** The certificate a server last offered that the device refused, if any. */
    fun rejectedFor(host: String): UntrustedCert? {
        val c = rejected[host.lowercase()] ?: return null
        fun name(dn: String) = Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: dn
        val expired = runCatching { c.checkValidity(); false }.getOrDefault(true)
        return UntrustedCert(
            host = host,
            subject = name(c.subjectX500Principal.name),
            issuer = name(c.issuerX500Principal.name),
            validUntil = SimpleDateFormat("d MMM yyyy", com.vantaview.app.ui.I18n.locale).format(c.notAfter),
            expired = expired,
            selfSigned = c.subjectX500Principal == c.issuerX500Principal,
            fingerprint = fingerprint(c),
        )
    }
}
