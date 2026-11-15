// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.browse

import com.vantaview.app.BuildConfig
import com.vantaview.app.R
import com.vantaview.app.data.addon.AddonJson
import com.vantaview.app.data.addon.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The tile groups at the top of Movies and TV Series. */
enum class BrowseGroup(val title: String) {
    WATCH("Watch now"), GENRES("Genres"), DECADES("Decades"), THEMED("Themed"), INTERNATIONAL("International"),
}

/**
 * One tile. Its films or series come from TMDB when the app has a TMDB key (every tile), or from
 * Cinemeta (Watch now, genres and decades). [art] is the app's own picture for it.
 */
data class BrowseTile(
    val key: String,
    val title: String,
    val group: BrowseGroup,
    val art: Int,
    /** For films, series or both. */
    val movie: Boolean = true,
    val series: Boolean = true,
    val list: String? = null,
    val tmdbMovieGenre: Int? = null,
    val tmdbTvGenre: Int? = null,
    val cinemetaGenre: String? = null,
    val years: IntRange? = null,
    val keyword: String? = null,
    val country: String? = null,
    /** Shown on Kids profiles. */
    val kids: Boolean = false,
) {
    fun forType(type: String) = if (type == "series") series else movie
}

private fun g(key: String, title: String, art: Int, movie: Int?, tv: Int?, cinemeta: String?, kids: Boolean = false, movieOnly: Boolean = false, tvOnly: Boolean = false) =
    BrowseTile(key, title, BrowseGroup.GENRES, art, movie = !tvOnly, series = !movieOnly, tmdbMovieGenre = movie, tmdbTvGenre = tv, cinemetaGenre = cinemeta, kids = kids)

private fun decade(start: Int, art: Int) =
    BrowseTile("y$start", "${start}s", BrowseGroup.DECADES, art, years = start..start + 9, cinemetaGenre = "year")

private fun theme(key: String, title: String, art: Int, keyword: String) = BrowseTile(key, title, BrowseGroup.THEMED, art, keyword = keyword)

private fun country(code: String, title: String, art: Int) = BrowseTile("c_$code", title, BrowseGroup.INTERNATIONAL, art, country = code.uppercase())

val BROWSE_TILES: List<BrowseTile> = listOf(
    BrowseTile("trending", "Trending", BrowseGroup.WATCH, R.drawable.browse_trending, list = "trending"),
    BrowseTile("latest", "Latest", BrowseGroup.WATCH, R.drawable.browse_latest, list = "latest"),
    BrowseTile("toprated", "Top rated", BrowseGroup.WATCH, R.drawable.browse_toprated, list = "toprated"),

    g("action", "Action", R.drawable.browse_action, 28, 10759, "Action"),
    g("adventure", "Adventure", R.drawable.browse_adventure, 12, 10759, "Adventure", movieOnly = true),
    g("animation", "Animation", R.drawable.browse_animation, 16, 16, "Animation", kids = true),
    g("anime", "Anime", R.drawable.browse_anime, 16, 16, null),
    g("comedy", "Comedy", R.drawable.browse_comedy, 35, 35, "Comedy"),
    g("crime", "Crime", R.drawable.browse_crime, 80, 80, "Crime"),
    g("documentary", "Documentary", R.drawable.browse_documentary, 99, 99, "Documentary"),
    g("drama", "Drama", R.drawable.browse_drama, 18, 18, "Drama"),
    g("family", "Family", R.drawable.browse_family, 10751, 10751, "Family", kids = true),
    g("kids", "Kids", R.drawable.browse_kids, null, 10762, null, kids = true, tvOnly = true),
    g("fantasy", "Fantasy", R.drawable.browse_fantasy, 14, 10765, "Fantasy"),
    g("history", "History", R.drawable.browse_history, 36, null, "History", movieOnly = true),
    g("horror", "Horror", R.drawable.browse_horror, 27, null, "Horror", movieOnly = true),
    g("music", "Music", R.drawable.browse_music, 10402, null, "Music", movieOnly = true),
    g("mystery", "Mystery", R.drawable.browse_mystery, 9648, 9648, "Mystery"),
    g("news", "News", R.drawable.browse_news, null, 10763, null, tvOnly = true),
    g("reality", "Reality", R.drawable.browse_reality, null, 10764, "Reality-TV", tvOnly = true),
    g("romance", "Romance", R.drawable.browse_romance, 10749, null, "Romance", movieOnly = true),
    g("scifi", "Sci-Fi", R.drawable.browse_scifi, 878, 10765, "Sci-Fi"),
    g("talk", "Talk", R.drawable.browse_talk, null, 10767, "Talk-Show", tvOnly = true),
    g("thriller", "Thriller", R.drawable.browse_thriller, 53, null, "Thriller", movieOnly = true),
    g("war", "War", R.drawable.browse_war, 10752, 10768, "War"),
    g("western", "Western", R.drawable.browse_western, 37, 37, "Western"),

    decade(1960, R.drawable.browse_y1960), decade(1970, R.drawable.browse_y1970), decade(1980, R.drawable.browse_y1980),
    decade(1990, R.drawable.browse_y1990), decade(2000, R.drawable.browse_y2000), decade(2010, R.drawable.browse_y2010),
    decade(2020, R.drawable.browse_y2020),

    theme("aliens", "Aliens", R.drawable.browse_aliens, "alien"),
    theme("disaster", "Disaster", R.drawable.browse_disaster, "disaster"),
    theme("heists", "Heists", R.drawable.browse_heists, "heist"),
    theme("holidays", "Holidays", R.drawable.browse_holidays, "christmas"),
    theme("space", "Space", R.drawable.browse_space, "outer space"),
    theme("superheroes", "Superheroes", R.drawable.browse_superheroes, "superhero"),
    theme("zombies", "Zombies", R.drawable.browse_zombies, "zombie"),
    theme("sport", "Sport", R.drawable.browse_sport, "sports"),
    theme("monsters", "Monsters", R.drawable.browse_monsters, "monster"),
    theme("timetravel", "Time travel", R.drawable.browse_timetravel, "time travel"),
    theme("roadtrip", "Road trip", R.drawable.browse_roadtrip, "road trip"),
    theme("survival", "Survival", R.drawable.browse_survival, "survival"),

    country("ar", "Argentina", R.drawable.browse_flag_ar), country("au", "Australia", R.drawable.browse_flag_au),
    country("br", "Brazil", R.drawable.browse_flag_br), country("ca", "Canada", R.drawable.browse_flag_ca),
    country("cn", "China", R.drawable.browse_flag_cn), country("dk", "Denmark", R.drawable.browse_flag_dk),
    country("fr", "France", R.drawable.browse_flag_fr), country("de", "Germany", R.drawable.browse_flag_de),
    country("in", "India", R.drawable.browse_flag_in), country("ie", "Ireland", R.drawable.browse_flag_ie),
    country("it", "Italy", R.drawable.browse_flag_it), country("jp", "Japan", R.drawable.browse_flag_jp),
    country("mx", "Mexico", R.drawable.browse_flag_mx), country("ng", "Nigeria", R.drawable.browse_flag_ng),
    country("no", "Norway", R.drawable.browse_flag_no), country("pl", "Poland", R.drawable.browse_flag_pl),
    country("kr", "South Korea", R.drawable.browse_flag_kr), country("es", "Spain", R.drawable.browse_flag_es),
    country("se", "Sweden", R.drawable.browse_flag_se), country("th", "Thailand", R.drawable.browse_flag_th),
    country("tr", "Turkey", R.drawable.browse_flag_tr), country("gb", "United Kingdom", R.drawable.browse_flag_gb),
    country("us", "United States", R.drawable.browse_flag_us),
)

/** Films and series for the tiles: TMDB when the app has a key, Cinemeta otherwise. */
@Singleton
class BrowseRepository @Inject constructor(private val http: OkHttpClient) {
    private val token = BuildConfig.TMDB_ACCESS_TOKEN
    val hasTmdb: Boolean get() = token.isNotBlank()
    private val cache = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val keywordIds = ConcurrentHashMap<String, String>()

    /** The tiles for Movies ("movie") or TV Series ("series"). */
    fun tiles(type: String, kids: Boolean): List<BrowseTile> = BROWSE_TILES.filter { t ->
        t.forType(type) && (!kids || t.kids) && (hasTmdb || (t.list != null || t.cinemetaGenre != null))
    }

    fun tile(key: String): BrowseTile? = BROWSE_TILES.firstOrNull { it.key == key }

    /** One page (from 1) of a tile. */
    suspend fun page(type: String, tile: BrowseTile, page: Int, kids: Boolean): List<MetaPreview> =
        if (hasTmdb) tmdbPage(type, tile, page, kids) else cinemetaPage(type, tile, page)

    // ---- TMDB ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun tmdb(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val url = "https://api.themoviedb.org/3/$path"
        cache[url]?.let { (at, j) -> if (System.currentTimeMillis() - at < 3 * 3_600_000L) return@withContext j }
        runCatching {
            // Works with either TMDB credential: the long API Read Access Token (sent as a Bearer
            // header) or the short 32-character API key (sent as ?api_key=).
            val shortKey = token.length == 32 && token.all { it.isLetterOrDigit() }
            val b = Request.Builder().header("Accept", "application/json")
            if (shortKey) b.url(url + (if ('?' in url) "&" else "?") + "api_key=$token") else b.url(url).header("Authorization", "Bearer $token")
            val r = b.build()
            http.newCall(r).execute().use { res -> if (res.isSuccessful) JSONObject(res.body!!.string()) else null }
        }.getOrNull()?.also { cache[url] = System.currentTimeMillis() to it }
    }

    private suspend fun keyword(name: String): String? {
        keywordIds[name]?.let { return it }
        val id = tmdb("search/keyword?query=${enc(name)}")?.optJSONArray("results")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
        if (id != null) keywordIds[name] = id
        return id
    }

    private suspend fun tmdbPage(type: String, t: BrowseTile, page: Int, kids: Boolean): List<MetaPreview> {
        val tv = type == "series"
        val kind = if (tv) "tv" else "movie"
        val path = when (t.list) {
            "trending" -> "trending/$kind/week?page=$page"
            "latest" -> if (tv) "tv/on_the_air?page=$page" else "movie/now_playing?page=$page"
            "toprated" -> "$kind/top_rated?page=$page"
            else -> buildString {
                append("discover/$kind?include_adult=false&sort_by=popularity.desc&page=$page")
                (if (tv) t.tmdbTvGenre else t.tmdbMovieGenre)?.let { append("&with_genres=$it") }
                if (t.key == "anime") append("&with_original_language=ja")
                t.country?.let { append("&with_origin_country=$it") }
                t.years?.let { y ->
                    val f = if (tv) "first_air_date" else "primary_release_date"
                    append("&$f.gte=${y.first}-01-01&$f.lte=${y.last}-12-31&vote_count.gte=50")
                }
                t.keyword?.let { k -> keyword(k)?.let { append("&with_keywords=$it") } ?: return emptyList() }
                if (kids && !tv) append("&certification_country=GB&certification.lte=PG")
            }
        }
        val arr = tmdb(path)?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val name = o.optString(if (tv) "name" else "title").ifBlank { return@mapNotNull null }
            val date = o.optString(if (tv) "first_air_date" else "release_date")
            MetaPreview(
                id = "tmdb:$kind:$id", type = type, name = name,
                poster = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" },
                year = date.take(4).ifBlank { null },
                rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.UK, it) },
            )
        }
    }

    /** The IMDb id add-ons use, for a TMDB title. Null if TMDB doesn't know it. */
    suspend fun imdbId(id: String): String? {
        val (_, kind, n) = id.split(':').let { Triple(it.getOrNull(0), it.getOrNull(1), it.getOrNull(2)) }
        if (kind == null || n == null) return null
        return tmdb("$kind/$n/external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
    }

    // ---- Cinemeta (no TMDB key) ----

    private suspend fun cinemetaPage(type: String, t: BrowseTile, page: Int): List<MetaPreview> = withContext(Dispatchers.IO) {
        val (catalog, genre) = when {
            t.list == "trending" -> "top" to null
            t.list == "latest" -> "year" to Calendar.getInstance().get(Calendar.YEAR).toString()
            t.list == "toprated" -> "imdbRating" to null
            t.years != null -> "year" to (t.years.first + page - 1).takeIf { it <= t.years.last && it <= Calendar.getInstance().get(Calendar.YEAR) }?.toString()
            else -> "top" to t.cinemetaGenre
        }
        if (t.years != null && genre == null) return@withContext emptyList()
        // Decades step a year a page; the others a page of 100 at a time.
        val skip = if (t.years != null) 0 else (page - 1) * 100
        val extra = listOfNotNull(genre?.let { "genre=${enc(it)}" }, skip.takeIf { it > 0 }?.let { "skip=$it" }).joinToString("&")
        val url = "https://v3-cinemeta.strem.io/catalog/$type/$catalog" + (if (extra.isNotEmpty()) "/$extra" else "") + ".json"
        val json = cache[url]?.second ?: runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
        }.getOrNull()?.also { cache[url] = System.currentTimeMillis() to it }
        val arr = json?.optJSONArray("metas") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { AddonJson.preview(it, type) }
    }
}
