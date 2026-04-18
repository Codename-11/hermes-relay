package com.hermesandroid.relay.update

/**
 * Loose SemVer comparator — enough for our version-check needs without
 * pulling in a full SemVer library.
 *
 * Rules:
 * - Strips a leading "v" (e.g. `v0.5.0` → `0.5.0`).
 * - Splits on "." and takes the leading digits of each segment; missing
 *   segments are treated as 0 so `0.5` and `0.5.0` compare equal.
 * - Pre-release suffixes (`0.6.0-rc.1`) are dropped — the bare version
 *   is used. That means we treat `0.6.0-rc.1` and `0.6.0` as equal; we
 *   don't ship prereleases via `/releases/latest` (GitHub excludes them
 *   automatically) so this simplification is safe.
 *
 * Returns: negative if [current] < [latest], 0 if equal, positive if >.
 */
internal fun compareVersions(current: String, latest: String): Int {
    val c = tokenize(current)
    val l = tokenize(latest)
    val size = maxOf(c.size, l.size)
    for (i in 0 until size) {
        val ci = c.getOrElse(i) { 0 }
        val li = l.getOrElse(i) { 0 }
        if (ci != li) return ci.compareTo(li)
    }
    return 0
}

private fun tokenize(raw: String): List<Int> {
    return raw.trim()
        .removePrefix("v")
        .substringBefore('-')                 // drop pre-release suffix
        .substringBefore('+')                 // drop build metadata
        .split('.')
        .map { seg -> seg.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
