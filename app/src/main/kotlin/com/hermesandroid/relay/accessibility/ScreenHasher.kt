package com.hermesandroid.relay.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.relay.accessibility.ScreenReader.Companion.MAX_NODES
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * Phase 3 — bridge feature expansion, work unit A5.
 *
 * Cheap change detection for agent navigation loops. Computes a SHA-256
 * hash over the full multi-window accessibility tree so the agent can
 * ask "did anything change since last iteration?" with ~100x less data
 * than a full [ScreenReader.readScreen] round-trip.
 *
 * # Fingerprint field set (hash stability is load-bearing)
 *
 * Each interesting node contributes:
 *
 *   className | text | contentDescription | bounds | viewIdResourceName
 *
 * Joined per-node with `|`, and joined between nodes with `\u001e`
 * (ASCII record separator) so a literal `|` in text can't collide with
 * field separators. The concatenated string is hashed with SHA-256 and
 * returned as lowercase hex.
 *
 * ## Why these fields
 *  * **className** — structural identity of the widget
 *  * **text** — what the user sees; primary content change signal
 *  * **contentDescription** — screen-reader label, relevant for icon
 *    buttons whose visible text is empty
 *  * **bounds** — layout geometry; catches appearance of new dialogs,
 *    bottom sheets, and popups
 *  * **viewIdResourceName** — stable across recompositions, lets us
 *    distinguish nodes that happen to share text (e.g. two "OK" buttons)
 *
 * ## Why NOT these
 *  * **isFocused / accessibility focus** — keyboard navigation toggles
 *    focus without any visible content change; would cause the hash to
 *    churn on arrow-key presses
 *  * **isSelected** — same reasoning as focus; Material chips etc.
 *    flip selected state during the ripple animation
 *  * **isEnabled / isClickable** — these almost always co-vary with
 *    text/bounds and dragging them in adds noise without signal
 *  * **timestamps** — no timestamps in the fingerprint, obviously
 *  * **window index (w<N>:<M> nodeId)** — the window index is stable
 *    across reads within a snapshot, but including it in the fingerprint
 *    is redundant with bounds (windows are geographically disjoint) and
 *    would make the hash brittle to window-list reorderings the agent
 *    doesn't care about
 *
 * ## Known limitation
 * Apps that put a live counter in a text field (e.g. "Downloading…
 * 3s", scrolling tickers, animated progress %) will churn the hash
 * every frame. The agent's calling tool documents this and recommends
 * `android_read_screen` for those edge cases.
 *
 * # Traversal
 *
 * Walks each provided root with the same child-recycling contract as
 * [ScreenReader.walk] — children are `.recycle()`'d in `try/finally`,
 * the input roots are NOT recycled (the caller owns their lifetime,
 * matching [ScreenReader.readScreen]'s convention).
 *
 * Node count is capped at [MAX_NODES] total across all windows so a
 * pathological grid can't OOM the hasher; if the cap is hit we still
 * return a hash of what we collected and set [ScreenHashResult.truncated]
 * = true.
 *
 * # Usage pattern
 *
 * ```kotlin
 * // A5 — when P1 multi-window lands, this will be
 * //   service.snapshotAllWindows() -> List<AccessibilityNodeInfo>
 * // Until then callers pass a singleton list of the active root.
 * val roots: List<AccessibilityNodeInfo> = listOfNotNull(service.snapshotRoot())
 * val hash = ScreenHasher().screenHash(roots)
 * ```
 */
class ScreenHasher {

    companion object {
        /** ASCII record separator (0x1E) — unlikely to appear in UI text. */
        private const val RECORD_SEPARATOR = '\u001E'

        /** Intra-node field separator. */
        private const val FIELD_SEPARATOR = '|'

        /** SHA-256 digest length in hex chars (64). Used by tests. */
        const val HASH_HEX_LENGTH = 64
    }

    @Serializable
    data class ScreenHashResult(
        val hash: String,
        val nodeCount: Int,
        val truncated: Boolean = false,
    )

    @Serializable
    data class DiffScreenResult(
        val changed: Boolean,
        val hash: String,
        val nodeCount: Int,
        val truncated: Boolean = false,
    )

    /**
     * Walk the multi-window tree under [roots] and return a stable
     * SHA-256 fingerprint.
     *
     * Does NOT recycle [roots] — caller owns them, same contract as
     * [ScreenReader.readScreen].
     */
    fun screenHash(roots: List<AccessibilityNodeInfo>): ScreenHashResult {
        val buf = StringBuilder(2048)
        var count = 0
        var truncated = false

        for (root in roots) {
            if (count >= MAX_NODES) {
                truncated = true
                break
            }
            val hit = walk(root, buf, count)
            count = hit.count
            if (hit.truncated) {
                truncated = true
                break
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(buf.toString().toByteArray(Charsets.UTF_8))
        return ScreenHashResult(
            hash = digest.toHex(),
            nodeCount = count,
            truncated = truncated,
        )
    }

    /**
     * Compute the current hash and compare against [previousHash].
     * Always returns both the new hash and the node count so the agent
     * can update its reference without a second round-trip.
     */
    fun diffScreen(
        roots: List<AccessibilityNodeInfo>,
        previousHash: String,
    ): DiffScreenResult {
        val current = screenHash(roots)
        return DiffScreenResult(
            changed = current.hash != previousHash,
            hash = current.hash,
            nodeCount = current.nodeCount,
            truncated = current.truncated,
        )
    }

    // ── Internals ──────────────────────────────────────────────────────

    /** Result of a walk — the running node count and a truncation flag. */
    private data class WalkResult(val count: Int, val truncated: Boolean)

    /**
     * Append fingerprints for [node] and all descendants into [out].
     * Mirrors [ScreenReader.walk]'s recycle contract exactly.
     */
    private fun walk(
        node: AccessibilityNodeInfo?,
        out: StringBuilder,
        startCount: Int,
    ): WalkResult {
        if (node == null) return WalkResult(startCount, false)
        if (startCount >= MAX_NODES) return WalkResult(startCount, true)

        var count = startCount
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val contentDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val clickable = node.isClickable
        val longClickable = node.isLongClickable
        val scrollable = node.isScrollable

        // Same "interesting" predicate as ScreenReader so the hash
        // covers exactly the nodes the agent can actually see/interact
        // with. Otherwise a layout container reshuffle would churn the
        // hash without any visible change.
        val interesting = (
            text != null || contentDesc != null ||
            clickable || longClickable || scrollable || node.isEditable
        ) && rect.width() > 0 && rect.height() > 0

        if (interesting) {
            appendFingerprint(
                out = out,
                className = node.className?.toString(),
                text = text,
                contentDescription = contentDesc,
                rect = rect,
                viewId = node.viewIdResourceName,
            )
            count += 1
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (count >= MAX_NODES) return WalkResult(count, true)
            val child = node.getChild(i) ?: continue
            try {
                val hit = walk(child, out, count)
                count = hit.count
                if (hit.truncated) return WalkResult(count, true)
            } finally {
                @Suppress("DEPRECATION")
                try { child.recycle() } catch (_: Throwable) { }
            }
        }

        return WalkResult(count, false)
    }

    private fun appendFingerprint(
        out: StringBuilder,
        className: String?,
        text: String?,
        contentDescription: String?,
        rect: Rect,
        viewId: String?,
    ) {
        if (out.isNotEmpty()) out.append(RECORD_SEPARATOR)
        out.append(className.orEmpty()).append(FIELD_SEPARATOR)
        out.append(text.orEmpty()).append(FIELD_SEPARATOR)
        out.append(contentDescription.orEmpty()).append(FIELD_SEPARATOR)
        // Compact bounds form — matches what the agent would see in
        // ScreenNode.bounds.toString() but cheaper to build.
        out.append(rect.left).append(',')
            .append(rect.top).append(',')
            .append(rect.right).append(',')
            .append(rect.bottom).append(FIELD_SEPARATOR)
        out.append(viewId.orEmpty())
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(this.size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }
}

private val HEX = "0123456789abcdef".toCharArray()
