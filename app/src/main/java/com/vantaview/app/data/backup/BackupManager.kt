// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.vantaview.app.BuildConfig
import com.vantaview.app.data.db.AddonEntity
import com.vantaview.app.data.db.FavouriteEntity
import com.vantaview.app.data.db.KidsCategoryEntity
import com.vantaview.app.data.db.ProfileEntity
import com.vantaview.app.data.db.ProviderEntity
import com.vantaview.app.data.db.VantaDb
import com.vantaview.app.data.debrid.DebridAccount
import com.vantaview.app.data.debrid.DebridService
import com.vantaview.app.data.debrid.DebridStore
import com.vantaview.app.data.history.WatchHistory
import com.vantaview.app.data.profile.ProfileSession
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.data.security.CredentialCrypto
import com.vantaview.app.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What's in a backup file, shown before restoring. */
data class BackupInfo(
    val createdAt: Long,
    val appVersion: String,
    val profiles: Int,
    val playlists: Int,
    val addons: Int,
    val locked: Boolean,
)

/** A backup saved by the app (automatic ones, or ones saved where there was no file picker). */
data class StoredBackup(val name: String, val modified: Long, val uri: Uri)

enum class RestoreMode { REPLACE, MERGE }

/**
 * Backs up and restores everything a person sets up: profiles, playlists and their logins, favourites,
 * watch history, add-ons, debrid accounts and settings. Logins are locked with a backup password.
 * Channels and the TV guide aren't saved: they're downloaded again after a restore.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: VantaDb,
    private val crypto: CredentialCrypto,
    private val session: ProfileSession,
    private val history: WatchHistory,
    private val debrid: DebridStore,
    private val providers: ProviderRepository,
    private val appearance: com.vantaview.app.data.AppearanceSettings,
    @AppScope private val scope: CoroutineScope,
) {
    private val sp = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** Settings files copied into a backup. ("guide" is a cache; "debrid" goes in the locked part.) */
    private val prefNames = listOf("vantaview", "history", "countries", "playback", "parental", "appearance", "sources")

    private val _lastAuto = MutableStateFlow(sp.getLong("last_auto", 0))
    val lastAuto: StateFlow<Long> = _lastAuto

    // ---- Remembered password and automatic backups ----

    /** The backup password, kept encrypted on this device so automatic backups can include logins. */
    var rememberedPassword: String?
        get() = sp.getString("password", null)?.let { crypto.decrypt(it) }?.ifEmpty { null }
        set(v) = sp.edit().apply { if (v.isNullOrEmpty()) remove("password") else putString("password", crypto.encrypt(v)) }.apply()

    var autoEnabled: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** A folder the user picked for backups (phones), or null for the app's own folder. */
    var folder: Uri?
        get() = sp.getString("folder", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("folder", v?.toString()).apply()

    /** The app's own backup folder. Note: Android deletes it if the app is uninstalled. */
    val appFolder: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups").apply { mkdirs() }

    private var watching = false

    /** Starts watching for changes to playlists and profiles; each change (after a quiet 15 s) saves a backup. */
    @OptIn(FlowPreview::class)
    fun startAutomatic() {
        if (watching) return
        watching = true
        scope.launch(Dispatchers.IO) {
            combine(db.backup().watchProviders(), db.backup().watchProfiles()) { a, b -> a to b }
                .distinctUntilChanged()
                .drop(1) // the state at start-up isn't a change
                .debounce(15_000)
                .collect { if (autoEnabled) runCatching { saveAutomatic() } }
        }
    }

    suspend fun saveAutomatic() = withContext(Dispatchers.IO) {
        val text = build(rememberedPassword)
        val name = "vantaview-auto-" + stamp() + ".vvbackup"
        writeToFolder(name, text)
        prune("vantaview-auto-", keep = 5)
        val now = System.currentTimeMillis()
        sp.edit().putLong("last_auto", now).apply()
        _lastAuto.value = now
    }

    /** Saves into the chosen folder, or the app folder. Returns where it went, for the message. */
    suspend fun writeToFolder(name: String, text: String): String = withContext(Dispatchers.IO) {
        val tree = folder?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.canWrite() }
        if (tree != null) {
            val f = tree.createFile("application/octet-stream", name) ?: throw IllegalStateException("Couldn't create the backup file")
            context.contentResolver.openOutputStream(f.uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("Couldn't write the backup file")
            tree.name ?: com.vantaview.app.ui.tr("your chosen folder")
        } else {
            File(appFolder, name).writeText(text)
            appFolder.absolutePath
        }
    }

    private fun prune(prefix: String, keep: Int) {
        stored().filter { it.name.startsWith(prefix) }.sortedByDescending { it.name }.drop(keep).forEach { b ->
            runCatching {
                if (b.uri.scheme == "file") File(b.uri.path!!).delete()
                else DocumentFile.fromSingleUri(context, b.uri)?.delete()
            }
        }
    }

    /** Backups saved by the app, newest first (from the chosen folder and the app folder). */
    fun stored(): List<StoredBackup> {
        val out = ArrayList<StoredBackup>()
        folder?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.canRead() }?.listFiles()?.forEach { f ->
            val n = f.name ?: return@forEach
            if (n.endsWith(".vvbackup")) out += StoredBackup(n, f.lastModified(), f.uri)
        }
        appFolder.listFiles()?.forEach { f ->
            if (f.name.endsWith(".vvbackup")) out += StoredBackup(f.name, f.lastModified(), Uri.fromFile(f))
        }
        return out.sortedByDescending { it.modified }
    }

    fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Couldn't open that file")

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())

    // ---- Making a backup ----

    /** Builds the backup file's text. With no password, logins and debrid keys are left out. */
    suspend fun build(password: String?): String = withContext(Dispatchers.IO) {
        val dao = db.backup()
        val profiles = dao.profiles()
        val provs = dao.providers()
        val root = JSONObject()
            .put("format", "vantaview-backup").put("version", 1)
            .put("createdAt", System.currentTimeMillis()).put("appVersion", BuildConfig.VERSION_NAME)
        root.put("profiles", JSONArray().apply {
            profiles.forEach { p ->
                put(JSONObject().put("id", p.id).put("name", p.name).put("avatar", p.avatar).put("isKids", p.isKids)
                    .put("pinHash", p.pinHash ?: "").put("pinSalt", p.pinSalt ?: "").put("createdAt", p.createdAt))
            }
        })
        root.put("playlists", JSONArray().apply {
            provs.forEach { p ->
                put(JSONObject().put("id", p.id).put("profileId", p.profileId).put("name", p.name).put("kind", p.kind)
                    .put("baseUrl", p.baseUrl).put("username", p.username).put("epgUrl", p.epgUrl ?: "").put("createdAt", p.createdAt))
            }
        })
        root.put("favourites", JSONArray().apply {
            dao.favourites().forEach { f -> put(JSONObject().put("providerId", f.providerId).put("remoteId", f.remoteId).put("addedAt", f.addedAt)) }
        })
        root.put("kidsCategories", JSONArray().apply {
            dao.kidsCategories().forEach { k ->
                put(JSONObject().put("profileId", k.profileId).put("providerId", k.providerId).put("categoryRemoteId", k.categoryRemoteId))
            }
        })
        root.put("addons", JSONArray().apply {
            dao.addons().forEach { a ->
                put(JSONObject().put("profileId", a.profileId).put("manifestUrl", a.manifestUrl).put("name", a.name)
                    .put("manifestJson", a.manifestJson).put("enabled", a.enabled).put("position", a.position).put("addedAt", a.addedAt))
            }
        })
        root.put("settings", JSONObject().apply { prefNames.forEach { n -> put(n, dumpPrefs(context.getSharedPreferences(n, Context.MODE_PRIVATE))) } })

        if (!password.isNullOrEmpty()) {
            val secrets = JSONObject()
            secrets.put("passwords", JSONObject().apply { provs.forEach { p -> put(p.id.toString(), crypto.decrypt(p.passwordEnc)) } })
            secrets.put("debrid", JSONArray().apply {
                debrid.all.forEach { a ->
                    put(JSONObject().put("service", a.service.id).put("label", a.label).put("token", a.token)
                        .put("refresh", a.refreshToken ?: "").put("clientId", a.clientId ?: "").put("clientSecret", a.clientSecret ?: "")
                        .put("expiresAt", a.expiresAt).put("premiumUntil", a.premiumUntil))
                }
            })
            root.put("locked", BackupCrypto.lock(secrets.toString(), password))
        }
        root.toString()
    }

    private fun dumpPrefs(p: SharedPreferences): JSONObject = JSONObject().apply {
        p.all.forEach { (k, v) ->
            val o = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            }
            if (o != null) put(k, o)
        }
    }

    private fun loadPrefs(p: SharedPreferences, o: JSONObject?, clear: Boolean) {
        val e = p.edit()
        if (clear) e.clear()
        o?.keys()?.forEach { k ->
            val x = o.optJSONObject(k) ?: return@forEach
            when (x.optString("t")) {
                "s" -> e.putString(k, x.optString("v"))
                "b" -> e.putBoolean(k, x.optBoolean("v"))
                "i" -> e.putInt(k, x.optInt("v"))
                "l" -> e.putLong(k, x.optLong("v"))
                "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                "set" -> x.optJSONArray("v")?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
            }
        }
        e.commit()
    }

    // ---- Restoring ----

    fun inspect(text: String): BackupInfo {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || o.optString("format") != "vantaview-backup") throw IllegalStateException("That isn't a VantaView backup file")
        return BackupInfo(
            createdAt = o.optLong("createdAt"),
            appVersion = o.optString("appVersion"),
            profiles = o.optJSONArray("profiles")?.length() ?: 0,
            playlists = o.optJSONArray("playlists")?.length() ?: 0,
            addons = o.optJSONArray("addons")?.length() ?: 0,
            locked = o.has("locked"),
        )
    }

    /** Restores a backup. A wrong password throws WrongPasswordException before anything is changed. */
    suspend fun restore(text: String, password: String?, mode: RestoreMode) = withContext(Dispatchers.IO) {
        val o = JSONObject(text)
        inspect(text)
        val secrets = o.optJSONObject("locked")?.let { box ->
            if (password.isNullOrEmpty()) null else JSONObject(BackupCrypto.unlock(box, password))
        }
        val passwords = secrets?.optJSONObject("passwords")
        fun arr(n: String): List<JSONObject> = o.optJSONArray(n)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

        val profiles = arr("profiles").map { p ->
            ProfileEntity(
                id = p.optLong("id"), name = p.optString("name"), avatar = p.optInt("avatar"), isKids = p.optBoolean("isKids"),
                pinHash = p.optString("pinHash").ifEmpty { null }, pinSalt = p.optString("pinSalt").ifEmpty { null },
                createdAt = p.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        val playlists = arr("playlists").map { p ->
            ProviderEntity(
                id = p.optLong("id"), profileId = p.optLong("profileId"), name = p.optString("name"), kind = p.optString("kind"),
                baseUrl = p.optString("baseUrl"), username = p.optString("username"),
                passwordEnc = passwords?.optString(p.optLong("id").toString())?.let(crypto::encrypt) ?: "",
                epgUrl = p.optString("epgUrl").ifEmpty { null }, createdAt = p.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        val favourites = arr("favourites").map { FavouriteEntity(it.optLong("providerId"), it.optString("remoteId"), it.optLong("addedAt")) }
        val kids = arr("kidsCategories").map { KidsCategoryEntity(it.optLong("profileId"), it.optLong("providerId"), it.optString("categoryRemoteId")) }
        val addons = arr("addons").map { a ->
            AddonEntity(
                profileId = a.optLong("profileId"), manifestUrl = a.optString("manifestUrl"), name = a.optString("name"),
                manifestJson = a.optString("manifestJson"), enabled = a.optBoolean("enabled", true),
                position = a.optInt("position"), addedAt = a.optLong("addedAt"),
            )
        }
        val debridAccounts = secrets?.optJSONArray("debrid")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .mapNotNull { d ->
                val service = DebridService.byId(d.optString("service")) ?: return@mapNotNull null
                DebridAccount(
                    service = service, label = d.optString("label"), token = d.optString("token"),
                    refreshToken = d.optString("refresh").ifEmpty { null }, clientId = d.optString("clientId").ifEmpty { null },
                    clientSecret = d.optString("clientSecret").ifEmpty { null }, expiresAt = d.optLong("expiresAt"),
                    premiumUntil = d.optLong("premiumUntil"),
                )
            }
        val settings = o.optJSONObject("settings")

        if (mode == RestoreMode.REPLACE) {
            // Everything goes back exactly as it was, with the same ids, so settings that mention ids still match.
            db.clearAllTables()
            val dao = db.backup()
            dao.putProfiles(profiles)
            dao.putProviders(playlists)
            dao.putFavourites(favourites)
            dao.putKidsCategories(kids)
            dao.putAddons(addons)
            prefNames.forEach { n -> loadPrefs(context.getSharedPreferences(n, Context.MODE_PRIVATE), settings?.optJSONObject(n), clear = true) }
            context.getSharedPreferences("guide", Context.MODE_PRIVATE).edit().clear().commit()
            if (secrets != null) {
                debrid.all.forEach { debrid.remove(it.service) }
                debridAccounts.forEach { debrid.put(it) }
            }
            val current = context.getSharedPreferences("vantaview", Context.MODE_PRIVATE).getLong("current_profile", 0L)
            session.set(if (profiles.any { it.id == current }) current else profiles.firstOrNull()?.id ?: 0L)
        } else {
            merge(profiles, playlists, favourites, kids, addons, debridAccounts, settings)
        }
        history.reload()
        appearance.reload()
        // Download channels for every playlist again.
        db.backup().providers().forEach { p -> providers.sync(p.id) }
    }

    /** Adds what's missing: profiles by name, playlists by address and login, add-ons by link. */
    private suspend fun merge(
        profiles: List<ProfileEntity>,
        playlists: List<ProviderEntity>,
        favourites: List<FavouriteEntity>,
        kids: List<KidsCategoryEntity>,
        addons: List<AddonEntity>,
        debridAccounts: List<DebridAccount>,
        settings: JSONObject?,
    ) {
        val dao = db.backup()
        val haveProfiles = dao.profiles()
        val profileMap = HashMap<Long, Long>()
        profiles.forEach { p ->
            val match = haveProfiles.firstOrNull { it.name.equals(p.name, true) && it.isKids == p.isKids }
            profileMap[p.id] = match?.id ?: dao.addProfile(p.copy(id = 0))
        }
        val haveProviders = dao.providers()
        val providerMap = HashMap<Long, Long>()
        playlists.forEach { p ->
            val owner = profileMap[p.profileId] ?: return@forEach
            val match = haveProviders.firstOrNull {
                it.profileId == owner && it.kind == p.kind && it.baseUrl.equals(p.baseUrl, true) && it.username == p.username
            }
            providerMap[p.id] = match?.id ?: dao.addProvider(p.copy(id = 0, profileId = owner, lastSyncAt = 0, channelCount = 0))
        }
        dao.putFavourites(favourites.mapNotNull { f -> providerMap[f.providerId]?.let { f.copy(providerId = it) } })
        dao.putKidsCategories(kids.mapNotNull { k ->
            val pr = profileMap[k.profileId] ?: return@mapNotNull null
            val pv = providerMap[k.providerId] ?: return@mapNotNull null
            k.copy(profileId = pr, providerId = pv)
        })
        addons.forEach { a -> profileMap[a.profileId]?.let { owner -> dao.addAddon(a.copy(id = 0, profileId = owner)) } }
        debridAccounts.forEach { a -> if (debrid.all.none { it.service == a.service }) debrid.put(a) }

        // Watch history: add entries this device doesn't have yet, per profile.
        val hist = context.getSharedPreferences("history", Context.MODE_PRIVATE)
        val backupHist = settings?.optJSONObject("history")
        profileMap.forEach { (old, new) ->
            val theirs = backupHist?.optJSONObject("p_$old")?.optString("v")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return@forEach
            val mine = runCatching { JSONArray(hist.getString("p_$new", "[]")) }.getOrDefault(JSONArray())
            val keys = (0 until mine.length()).mapNotNull { mine.optJSONObject(it) }.map { it.optString("type") + ":" + it.optString("metaId") }.toHashSet()
            (0 until theirs.length()).mapNotNull { theirs.optJSONObject(it) }.forEach { e ->
                if (keys.add(e.optString("type") + ":" + e.optString("metaId"))) mine.put(e)
            }
            hist.edit().putString("p_$new", mine.toString()).commit()
        }
    }
}
