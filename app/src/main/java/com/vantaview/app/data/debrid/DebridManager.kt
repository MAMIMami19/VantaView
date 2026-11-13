// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.debrid

import com.vantaview.app.data.addon.AddonStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns torrent results from add-ons into playable links through the user's debrid accounts.
 * Accounts are tried in the order the user set; cached copies win over everything else.
 */
@Singleton
class DebridManager @Inject constructor(
    private val store: DebridStore,
    okHttp: OkHttpClient,
) {
    private val http = DebridHttp(okHttp)
    val realDebrid = RealDebridClient(http) { store.put(it) }
    val allDebrid = AllDebridClient(http)
    private val clients: Map<DebridService, DebridClient> = listOf(realDebrid, allDebrid, TorBoxClient(http), PremiumizeClient(http)).associateBy { it.service }

    fun client(s: DebridService): DebridClient = clients.getValue(s)

    val hasAccounts: Boolean get() = store.all.isNotEmpty()

    /** Checks the given key and returns the account to save. */
    suspend fun connectWithKey(service: DebridService, key: String): DebridAccount {
        val k = key.trim()
        if (k.isEmpty()) throw DebridException("Paste the API key first")
        val acc = DebridAccount(service, "", k)
        val info = client(service).accountInfo(acc)
        return acc.copy(label = info.label, premiumUntil = info.premiumUntil).also { store.put(it) }
    }

    suspend fun refreshInfo(service: DebridService): AccountInfo? {
        val a = store.get(service) ?: return null
        return client(service).accountInfo(a).also { store.put(a.copy(label = it.label.ifBlank { a.label }, premiumUntil = it.premiumUntil)) }
    }

    /**
     * Adds cache information to torrent results and puts them in play order: direct links and cached torrents
     * (by quality), then torrents whose state can't be checked in advance, then uncached torrents.
     * Without any debrid account, torrent results are left out.
     */
    suspend fun annotate(streams: List<AddonStream>): List<AddonStream> {
        val accounts = store.all
        if (accounts.isEmpty()) return streams.filter { it.infoHash == null }
        val hashes = streams.mapNotNull { it.infoHash?.lowercase() }.distinct()
        if (hashes.isEmpty()) return streams

        val results: Map<DebridService, Map<String, Boolean>?> = coroutineScope {
            accounts.map { a ->
                async {
                    a.service to withTimeoutOrNull(15_000) {
                        // AllDebrid's check adds and removes magnets, so only the top results are checked there.
                        val list = if (a.service == DebridService.ALL_DEBRID) hashes.take(12) else hashes.take(80)
                        runCatching { client(a.service).cached(a, list) }.getOrNull()
                    }
                }
            }.awaitAll().toMap()
        }

        val annotated = streams.map { s ->
            val h = s.infoHash?.lowercase() ?: return@map s
            s.copy(cache = accounts.associate { a ->
                a.service to when (results[a.service]?.get(h)) {
                    true -> Availability.CACHED
                    false -> Availability.UNCACHED
                    null -> Availability.UNKNOWN
                }
            })
        }
        return annotated.withIndex().sortedWith(compareBy({ tier(it.value) }, { it.index })).map { it.value }
    }

    fun tier(s: AddonStream): Int = when {
        s.infoHash == null -> 0
        s.cache.values.any { it == Availability.CACHED } -> 0
        s.cache.values.any { it == Availability.UNKNOWN } -> 1
        else -> 2
    }

    /**
     * Gets a playable link for a torrent result. Services that report it cached are tried first.
     * If none has it and [allowDownload] is set, it is sent to the first account to download, and [onProgress]
     * is called until it's ready (the caller cancels by leaving).
     */
    suspend fun resolve(
        s: AddonStream,
        season: Int?,
        episode: Int?,
        allowDownload: Boolean,
        onProgress: (DebridService, Int?) -> Unit,
    ): Resolve {
        val hash = s.infoHash ?: return Resolve.Failed("Not a torrent")
        val accounts = store.all
        if (accounts.isEmpty()) return Resolve.Failed("Connect a debrid account on the Sources page to play torrents")
        val req = TorrentRequest(hash, s.label, s.filename, s.fileIdx, season, episode, s.trackers)
        val ordered = accounts.sortedBy { a ->
            when (s.cache[a.service]) { Availability.CACHED -> 0; Availability.UNKNOWN, null -> 1; Availability.UNCACHED -> 2 }
        }
        var lastError: String? = null
        for (a in ordered) {
            if (s.cache[a.service] == Availability.UNCACHED) continue
            when (val r = runCatching { client(a.service).resolve(a, req, start = false) }.getOrElse { Resolve.Failed(it.message ?: "error") }) {
                is Resolve.Ready -> return r
                is Resolve.Failed -> lastError = "${a.service.label}: ${r.message}"
                else -> Unit
            }
        }
        if (!allowDownload) return lastError?.let { Resolve.Failed(it) } ?: Resolve.NotCached

        val target = accounts.first()
        while (true) {
            when (val r = runCatching { client(target.service).resolve(target, req, start = true) }.getOrElse { Resolve.Failed(it.message ?: "error") }) {
                is Resolve.Pending -> onProgress(target.service, r.percent)
                is Resolve.NotCached -> onProgress(target.service, null)
                else -> return r
            }
            delay(5_000)
        }
    }
}
