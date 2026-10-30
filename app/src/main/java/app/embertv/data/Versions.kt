package app.embertv.data

import java.text.Normalizer

/** Picture quality, as providers label it in titles or category names. */
enum class Quality(val badge: String, val title: String, val rank: Int) {
    UHD("4K", "4K Ultra HD", 4),
    FHD("FHD", "Full HD · 1080p", 3),
    HD("HD", "HD · 720p", 2),
    SD("SD", "SD", 1),
    UNKNOWN("", "Standard", 0),
}

/**
 * Providers often list the same film or show several times, once per quality
 * ("The Matrix (1999) 4K", "The Matrix (1999) FHD"…). This works out each copy's quality
 * and a key that's the same for all of them, so they can be shown once.
 */
object Versions {
    private fun tok(p: String) = Regex("(?<![A-Z0-9])(?:$p)(?![A-Z0-9])")

    private val UHD = tok("4K|UHD|2160P?|ULTRA ?HD")
    private val FHD = tok("FHD|1080P?|1080I|FULL ?HD")
    private val HD = tok("HD|720P?|HDTV|HDRIP")
    // Plain "SD" is left alone: it starts real titles too ("SD Gundam").
    private val SD = tok("480P?|576P?")
    private val EXTRAS = listOf(
        tok("HDR10\\+|HDR10|HDR") to "HDR",
        tok("DV|DOLBY ?VISION") to "Dolby Vision",
        tok("HEVC|H\\.?265|X265") to "HEVC",
        tok("ATMOS") to "Atmos",
        tok("3D") to "3D",
    )
    /** Everything removed from titles: quality and format tags. */
    private val TAGS = tok(
        "4K|UHD|2160P?|ULTRA ?HD|FHD|1080P?|1080I|FULL ?HD|HD|720P?|HDTV|HDRIP|480P?|576P?|" +
            "HDR10\\+|HDR10|HDR|DV|DOLBY ?VISION|HEVC|H\\.?265|X265|H\\.?264|X264|ATMOS|60FPS|50FPS|3D"
    )
    private val YEAR = Regex("(?<!\\d)(19[0-9]{2}|20[0-9]{2})(?!\\d)")
    private val LANGS = setOf(
        "en", "eng", "uk", "us", "usa", "gb", "fr", "de", "ger", "es", "spa", "it", "ita", "nl", "pt", "pl", "tr", "ar", "in",
        "hi", "multi", "sub", "subs", "dub", "vod", "vf", "vostfr", "ie", "ca", "au", "nf", "amz", "dsnp",
    )

    /** Uppercase without changing length, so match positions line up with the original. */
    private fun up(s: String) = String(CharArray(s.length) { s[it].uppercaseChar() })

    fun quality(name: String, categoryName: String? = null): Quality {
        val n = up(name)
        val fromName = when {
            UHD.containsMatchIn(n) -> Quality.UHD
            FHD.containsMatchIn(n) -> Quality.FHD
            HD.containsMatchIn(n) -> Quality.HD
            SD.containsMatchIn(n) -> Quality.SD
            else -> Quality.UNKNOWN
        }
        if (fromName != Quality.UNKNOWN || categoryName == null) return fromName
        return categoryQuality(categoryName) ?: Quality.UNKNOWN
    }

    /** The quality a whole category is named for ("4K Movies", "UHD", "FHD | Films"), if any. */
    fun categoryQuality(name: String): Quality? {
        val n = up(name)
        return when {
            UHD.containsMatchIn(n) -> Quality.UHD
            FHD.containsMatchIn(n) -> Quality.FHD
            HD.containsMatchIn(n) -> Quality.HD
            else -> null
        }
    }

    /** "HDR", "Dolby Vision", "HEVC"… mentioned in the title. */
    fun extras(name: String): List<String> {
        val n = up(name)
        return EXTRAS.filter { (r, _) -> r.containsMatchIn(n) }.map { it.second }
    }

    /** The title without quality tags: "The Matrix (1999) [4K HDR]" → "The Matrix (1999)". */
    fun cleanTitle(name: String): String {
        // Work on an uppercase copy to find tags, then cut the same ranges from the original.
        val upper = up(name)
        val sb = StringBuilder(name)
        for (m in TAGS.findAll(upper).toList().asReversed()) sb.delete(m.range.first, m.range.last + 1)
        var s = sb.toString()
        // Brackets left empty, and separators left dangling at either end or doubled up.
        repeat(2) {
            s = s.replace(Regex("[\\[(|{]\\s*[-+/,|]*\\s*[\\])|}]"), " ")
        }
        s = s.replace(Regex("\\s*([-|:·•/])\\s*(?=[-|:·•/])"), " ")
        s = s.replace(Regex("\\s{2,}"), " ").trim()
        fun sep(c: Char) = c == '-' || c == '|' || c == ':' || c == '·' || c == '•' || c == '/' || c == ',' || c.isWhitespace()
        s = s.trimEnd { sep(it) }
        // Keep a label the provider put first on purpose ("|UK| Wonka"); only tidy what removing a tag left.
        if (!sep(name.trimStart().firstOrNull() ?: 'x')) s = s.trimStart { sep(it) }
        return s.ifEmpty { name.trim() }
    }

    fun year(name: String): String? = YEAR.findAll(name).lastOrNull()?.value

    /** The same for every quality of a title: no tags, year, language prefix, case or punctuation. */
    fun key(name: String): String {
        var s = Normalizer.normalize(cleanTitle(name).lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        s = YEAR.replace(s, " ")
        s = s.replace("&", " and ")
        // Leading language or source labels like "EN - ", "|UK| ", "NF: ".
        while (true) {
            val m = Regex("^\\W*([a-z0-9+]{2,6})\\W*[-|:]\\s*").find(s) ?: break
            if (m.groupValues[1] !in LANGS) break
            s = s.substring(m.range.last + 1)
        }
        return s.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** Groups [items] by title, keeping years apart, in the order they first appear. */
    private fun <T> group(items: List<T>, name: (T) -> String): List<List<T>> {
        val byKey = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val k = key(name(item))
            if (k.isEmpty()) {
                byKey.getOrPut("#" + byKey.size) { ArrayList() }.add(item)
            } else {
                byKey.getOrPut(k) { ArrayList() }.add(item)
            }
        }
        val out = ArrayList<List<T>>()
        for (list in byKey.values) {
            if (list.size == 1) {
                out.add(list)
                continue
            }
            // Same title, different years (remakes) stay apart; copies without a year join the only year there is.
            val byYear = list.groupBy { year(name(it)) }
            val years = byYear.keys.filterNotNull()
            if (years.size <= 1) out.add(list) else {
                for (y in years) out.add(byYear[y].orEmpty())
                byYear[null]?.let { out.add(it) }
            }
        }
        return out
    }

    /** One movie per title. Each has [Movie.versions] (best first) when there's more than one, and its quality set. */
    fun mergeMovies(items: List<Movie>, categoryNames: Map<String, String>): List<Movie> =
        group(items) { it.name }.map { copies ->
            val tagged = copies.map { it.copy(quality = quality(it.name, categoryNames[it.categoryId])) }
                .distinctBy { it.streamId }
                .sortedByDescending { it.quality.rank }
            val best = tagged.firstOrNull { it.poster.isNotBlank() } ?: tagged.first()
            best.copy(name = cleanTitle(best.name), versions = if (tagged.size > 1) tagged else emptyList())
        }

    fun mergeSeries(items: List<Series>, categoryNames: Map<String, String>): List<Series> =
        group(items) { it.name }.map { copies ->
            val tagged = copies.map { it.copy(quality = quality(it.name, categoryNames[it.categoryId])) }
                .distinctBy { it.seriesId }
                .sortedByDescending { it.quality.rank }
            val best = tagged.firstOrNull { it.cover.isNotBlank() } ?: tagged.first()
            best.copy(name = cleanTitle(best.name), versions = if (tagged.size > 1) tagged else emptyList())
        }

    /** The copies of [title] in [pool], best first. */
    fun movieCopies(title: String, pool: List<Movie>, categoryNames: Map<String, String>): List<Movie> {
        val k = key(title)
        val y = year(title)
        return pool.asSequence()
            .filter { key(it.name) == k && (y == null || year(it.name).let { yy -> yy == null || yy == y }) }
            .map { it.copy(quality = quality(it.name, categoryNames[it.categoryId]), versions = emptyList()) }
            .distinctBy { it.streamId }
            .sortedByDescending { it.quality.rank }
            .toList()
    }

    fun seriesCopies(title: String, pool: List<Series>, categoryNames: Map<String, String>): List<Series> {
        val k = key(title)
        val y = year(title)
        return pool.asSequence()
            .filter { key(it.name) == k && (y == null || year(it.name).let { yy -> yy == null || yy == y }) }
            .map { it.copy(quality = quality(it.name, categoryNames[it.categoryId]), versions = emptyList()) }
            .distinctBy { it.seriesId }
            .sortedByDescending { it.quality.rank }
            .toList()
    }
}
