package com.winlator.star.communityconfigs

/** A canonical game ranked against a shortcut name, best first. */
data class GameCandidate(
    val game: CanonicalGame,
    val score: Double,
    val exact: Boolean,
)

/**
 * Fully offline name matcher — no per-lookup network calls. Normalizes a shortcut name
 * ("Crysis3Remastered" / "Crysis 3 Remastered") and the canonical names into token sets, then
 * ranks: exact-normalized > token-subset > best token overlap.
 */
object GameMatcher {

    // Edition / marketing suffix tokens stripped from BOTH sides so "Crysis 3 Remastered" and a
    // canonical "Crysis 3" still line up. Applied symmetrically → never hurts an exact match.
    private val NOISE_TOKENS = setOf(
        "the", "remastered", "remaster", "remake", "edition", "goty", "definitive", "deluxe",
        "complete", "enhanced", "ultimate", "hd", "gold", "collection", "anniversary",
        "directors", "cut", "repack", "reloaded", "codex", "steam",
    )

    /**
     * Splits a raw title into normalized tokens: lowercases, breaks camelCase / digit boundaries,
     * strips non-alphanumeric separators (underscores, punctuation) and drops edition-noise tokens.
     */
    fun tokenize(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // Insert spaces at camelCase and letter/digit boundaries: "Crysis3Remastered" → "Crysis 3 Remastered".
        val spaced = StringBuilder(raw.length * 2)
        val chars = raw.toCharArray()
        for (i in chars.indices) {
            val c = chars[i]
            if (i > 0) {
                val prev = chars[i - 1]
                val boundary =
                    (Character.isUpperCase(c) && !Character.isUpperCase(prev)) ||
                    (Character.isDigit(c) != Character.isDigit(prev) &&
                        (Character.isLetterOrDigit(c) && Character.isLetterOrDigit(prev)))
                if (boundary) spaced.append(' ')
            }
            spaced.append(c)
        }
        return spaced.toString()
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in NOISE_TOKENS }
    }

    /** Ranked candidates for [query] against [games]; empty when nothing plausibly overlaps. */
    fun match(query: String, games: List<CanonicalGame>): List<GameCandidate> {
        val q = tokenize(query)
        if (q.isEmpty()) return emptyList()
        val qSet = q.toSet()

        val candidates = ArrayList<GameCandidate>()
        for (game in games) {
            val g = tokenize(game.name)
            if (g.isEmpty()) continue
            val gSet = g.toSet()

            val exact = qSet == gSet
            val inter = qSet.count { it in gSet }
            if (inter == 0 && !exact) continue

            // Score primarily by how fully the CANONICAL name is covered by the shortcut: a game
            // name fully contained in the shortcut (e.g. "Dirt 3" ⊆ "dirt 3 colin mcrae", or
            // "Crysis 3" ⊆ "crysis 3 remastered") is a strong match and must not be diluted by the
            // shortcut's extra subtitle tokens. Blend with query coverage; tiny +inter bonus breaks
            // ties toward more shared tokens (so "Dirt 3" beats a "3"-only name).
            val covGame = inter.toDouble() / gSet.size
            val covQuery = inter.toDouble() / qSet.size
            val score = if (exact) 1.0 else (0.7 * covGame + 0.3 * covQuery + 0.001 * inter)
            if (score >= MIN_SCORE) candidates.add(GameCandidate(game, score, exact))
        }

        return candidates.sortedWith(
            compareByDescending<GameCandidate> { it.score }
                .thenByDescending { it.game.configCount }
        )
    }

    /**
     * Free-text search over the whole database — the manual-pick fallback for when auto-match is
     * wrong or empty. Ranks exact > prefix > substring > token-overlap, breaking ties by config count.
     */
    fun search(query: String, games: List<CanonicalGame>, limit: Int = 40): List<CanonicalGame> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val qTokens = tokenize(query).toSet()
        return games.asSequence()
            .mapNotNull { g ->
                val name = g.name.lowercase()
                val gTokens = tokenize(g.name).toSet()
                val hits = qTokens.count { it in gTokens }
                val rank = when {
                    name == q -> 4.0
                    name.startsWith(q) -> 3.0
                    name.contains(q) -> 2.0
                    hits > 0 -> 1.0 + hits.toDouble() / (qTokens.size + 1)
                    else -> 0.0
                }
                if (rank <= 0.0) null else g to (rank + g.configCount * 1e-6)
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * Reorders a game's device list so the ones matching the user's hardware surface first:
     * SoC match, then GPU/driver match, then the rest — each group keeping index order.
     */
    fun rankDevices(devices: List<CanonicalDevice>, userSoc: String?, userGpu: String?): List<CanonicalDevice> {
        if (devices.isEmpty()) return devices
        val soc = userSoc?.lowercase()?.takeIf { it.isNotBlank() }
        val gpu = userGpu?.lowercase()?.takeIf { it.isNotBlank() }
        if (soc == null && gpu == null) return devices

        fun rank(d: CanonicalDevice): Int = when {
            soc != null && d.soc.isNotBlank() && looselyMatches(d.soc.lowercase(), soc) -> 0
            gpu != null && d.gpu.isNotBlank() && looselyMatches(d.gpu.lowercase(), gpu) -> 1
            else -> 2
        }
        // Stable sort preserves original order within each rank bucket.
        return devices.sortedBy { rank(it) }
    }

    /** True when either string contains the other — tolerant of "Adreno 750" vs "Qualcomm Adreno (TM) 750". */
    private fun looselyMatches(a: String, b: String): Boolean =
        a == b || a.contains(b) || b.contains(a)

    private const val MIN_SCORE = 0.34
}
