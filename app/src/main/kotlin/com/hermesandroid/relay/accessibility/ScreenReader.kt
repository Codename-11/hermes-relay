package com.hermesandroid.relay.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable

/**
 * Phase 3 — accessibility `accessibility-runtime`
 *
 * Walks the active-window `AccessibilityNodeInfo` tree and produces a
 * structured, serializable snapshot the agent can reason about.
 *
 * The output shape is deliberately flat: the agent overwhelmingly cares
 * about *"what text can I see, and where is it?"* — not the exact widget
 * hierarchy. We emit one [ScreenNode] per interesting node (anything with
 * non-blank text, content description, or a click / long-click action) and
 * include its screen bounds so [ActionExecutor.tapText] can hand them to
 * `dispatchGesture` without re-walking the tree.
 *
 * The tree is bounded by [MAX_NODES] to prevent pathological apps (grids
 * with thousands of cells) from producing multi-megabyte screen dumps.
 * When the cap is hit we short-circuit traversal and set
 * [ScreenContent.truncated] = true so the agent knows to scroll if it
 * needs more.
 */
class ScreenReader {

    companion object {
        /**
         * Hard cap on node count. 512 is comfortable for a typical app
         * screen (most have 30–150 interesting nodes) while keeping the
         * wire payload under ~40 KB in the worst case.
         */
        const val MAX_NODES = 512

        /** Hard cap on individual text-field length before truncation. */
        const val MAX_TEXT_LEN = 2000
    }

    /**
     * Structured representation of a screen, ready for JSON serialization.
     * The [rootBounds] are in absolute screen pixels (what `dispatchGesture`
     * uses).
     */
    @Serializable
    data class ScreenContent(
        val packageName: String?,
        val rootBounds: Bounds,
        val nodes: List<ScreenNode>,
        val truncated: Boolean = false,
        val nodeCount: Int = nodes.size,
    )

    @Serializable
    data class ScreenNode(
        /**
         * Stable per-snapshot ID in the form `"w<windowIndex>:<sequentialIndex>"`
         * — introduced by P1 multi-window ScreenReader and reused here by
         * `searchNodes` so results can be fed back into `tap nodeId`.
         * Nullable for backward compat with snapshots produced before P1.
         */
        val nodeId: String? = null,
        val text: String? = null,
        val contentDescription: String? = null,
        val className: String? = null,
        val viewId: String? = null,
        val bounds: Bounds,
        val clickable: Boolean = false,
        val longClickable: Boolean = false,
        val scrollable: Boolean = false,
        val editable: Boolean = false,
        val focused: Boolean = false,
        val selected: Boolean = false,
        val enabled: Boolean = true,
    )

    @Serializable
    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isEmpty: Boolean get() = width <= 0 || height <= 0
    }

    /**
     * Traverse the tree rooted at [rootNode] and return a [ScreenContent]
     * snapshot.
     *
     * This method does NOT recycle [rootNode] — the caller owns it (the
     * caller also owns the lifetime contract with `rootInActiveWindow`).
     *
     * @param includeBounds when false, we still collect bounds for
     *        traversal decisions but zero them in the output to shrink the
     *        payload. The agent almost always wants bounds so this defaults
     *        true.
     */
    fun readScreen(
        rootNode: AccessibilityNodeInfo,
        includeBounds: Boolean = true,
    ): ScreenContent {
        val collected = ArrayList<ScreenNode>(128)
        val rootRect = Rect().also { rootNode.getBoundsInScreen(it) }
        val rootBounds = rootRect.toBoundsOrZero()

        val truncated = walk(rootNode, collected, includeBounds)

        return ScreenContent(
            packageName = rootNode.packageName?.toString(),
            rootBounds = rootBounds,
            nodes = collected,
            truncated = truncated,
        )
    }

    /**
     * Recursive walker. Returns `true` if the cap was hit (signaling
     * caller to mark output as truncated).
     */
    private fun walk(
        node: AccessibilityNodeInfo?,
        out: MutableList<ScreenNode>,
        includeBounds: Boolean,
    ): Boolean {
        if (node == null) return false
        if (out.size >= MAX_NODES) return true

        // Skip invisible nodes early — no visual, no interaction target.
        if (!node.isVisibleToUser) {
            // still walk children, some containers aren't marked visible
            // but host visible descendants
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = if (includeBounds) rect.toBoundsOrZero() else ZERO_BOUNDS

        val text = node.text?.toString()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LEN)
        val contentDesc = node.contentDescription?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TEXT_LEN)
        val clickable = node.isClickable
        val longClickable = node.isLongClickable
        val scrollable = node.isScrollable

        val interesting = (text != null || contentDesc != null ||
            clickable || longClickable || scrollable || node.isEditable) &&
            !bounds.isEmpty

        if (interesting) {
            out.add(
                ScreenNode(
                    text = text,
                    contentDescription = contentDesc,
                    className = node.className?.toString(),
                    viewId = node.viewIdResourceName,
                    bounds = bounds,
                    clickable = clickable,
                    longClickable = longClickable,
                    scrollable = scrollable,
                    editable = node.isEditable,
                    focused = node.isFocused,
                    selected = node.isSelected,
                    enabled = node.isEnabled,
                )
            )
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (out.size >= MAX_NODES) return true
            val child = node.getChild(i) ?: continue
            try {
                val hit = walk(child, out, includeBounds)
                if (hit) return true
            } finally {
                @Suppress("DEPRECATION")
                try { child.recycle() } catch (_: Throwable) { }
            }
        }

        return false
    }

    /**
     * Filtered search across all provided window roots. Used by
     * `android_find_nodes` — returns up to [limit] matches without
     * dumping the whole accessibility tree.
     *
     * Filter semantics (all optional, all ANDed):
     *  - [text]: case-insensitive substring match against each node's
     *    `text` OR `contentDescription`.
     *  - [className]: exact match against `node.className.toString()`.
     *  - [clickable]: when non-null, filters on `node.isClickable`.
     *
     * The underlying traversal honors [MAX_NODES] as a safety rail even
     * when [limit] is higher — we stop walking after visiting 512 nodes
     * regardless of how many matched. Node recycling follows the same
     * per-child `try/finally` pattern as [walk], so this function is
     * leak-free w.r.t. the accessibility node pool.
     *
     * Returns a list of [ScreenNode] in the same shape that
     * [readAllWindows] / [readScreen] emits, including the P1 `nodeId`
     * field (`"w<windowIndex>:<sequentialIndex>"`) so callers can feed
     * results back into `tap nodeId`.
     */
    fun searchNodes(
        roots: List<AccessibilityNodeInfo>,
        text: String? = null,
        className: String? = null,
        clickable: Boolean? = null,
        limit: Int = 20,
    ): List<ScreenNode> {
        if (limit <= 0) return emptyList()
        val loweredText = text?.takeIf { it.isNotBlank() }?.lowercase()
        val effectiveLimit = limit.coerceAtLeast(0)

        val out = ArrayList<ScreenNode>(effectiveLimit.coerceAtMost(64))
        // Shared visit counter across all windows — the MAX_NODES cap is
        // global, matching how `readAllWindows` (P1) bounds traversal.
        val visited = intArrayOf(0)

        for ((windowIndex, root) in roots.withIndex()) {
            if (out.size >= effectiveLimit) break
            if (visited[0] >= MAX_NODES) break
            searchWalk(
                node = root,
                windowIndex = windowIndex,
                nextIndex = intArrayOf(0),
                visited = visited,
                loweredText = loweredText,
                className = className,
                clickable = clickable,
                limit = effectiveLimit,
                out = out,
            )
        }
        return out
    }

    /**
     * Recursive walker for [searchNodes]. Returns nothing; accumulates
     * matches into [out] and respects both [MAX_NODES] (via [visited])
     * and [limit] (via `out.size`).
     *
     * [nextIndex] is the per-window sequential counter used to build the
     * `w<windowIndex>:<N>` node ID — mirrors the scheme P1 introduced in
     * [readAllWindows] so the two surfaces produce stable, comparable IDs.
     */
    private fun searchWalk(
        node: AccessibilityNodeInfo?,
        windowIndex: Int,
        nextIndex: IntArray,
        visited: IntArray,
        loweredText: String?,
        className: String?,
        clickable: Boolean?,
        limit: Int,
        out: MutableList<ScreenNode>,
    ) {
        if (node == null) return
        if (out.size >= limit) return
        if (visited[0] >= MAX_NODES) return

        visited[0] += 1
        val thisNodeIndex = nextIndex[0]
        nextIndex[0] += 1

        val nodeText = node.text?.toString()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LEN)
        val contentDesc = node.contentDescription?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TEXT_LEN)
        val nodeClassName = node.className?.toString()
        val nodeClickable = node.isClickable

        val matchesText = loweredText == null ||
            (nodeText?.lowercase()?.contains(loweredText) == true) ||
            (contentDesc?.lowercase()?.contains(loweredText) == true)
        val matchesClass = className == null || nodeClassName == className
        val matchesClickable = clickable == null || nodeClickable == clickable

        if (matchesText && matchesClass && matchesClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val bounds = rect.toBoundsOrZero()
            if (!bounds.isEmpty || loweredText != null || className != null) {
                out.add(
                    ScreenNode(
                        nodeId = "w$windowIndex:$thisNodeIndex",
                        text = nodeText,
                        contentDescription = contentDesc,
                        className = nodeClassName,
                        viewId = node.viewIdResourceName,
                        bounds = bounds,
                        clickable = nodeClickable,
                        longClickable = node.isLongClickable,
                        scrollable = node.isScrollable,
                        editable = node.isEditable,
                        focused = node.isFocused,
                        selected = node.isSelected,
                        enabled = node.isEnabled,
                    )
                )
                if (out.size >= limit) return
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (out.size >= limit) return
            if (visited[0] >= MAX_NODES) return
            val child = node.getChild(i) ?: continue
            try {
                searchWalk(
                    node = child,
                    windowIndex = windowIndex,
                    nextIndex = nextIndex,
                    visited = visited,
                    loweredText = loweredText,
                    className = className,
                    clickable = clickable,
                    limit = limit,
                    out = out,
                )
            } finally {
                @Suppress("DEPRECATION")
                try { child.recycle() } catch (_: Throwable) { }
            }
        }
    }

    /**
     * Find the first descendant node whose text or content-description
     * contains [needle] (case-insensitive). Used by
     * [ActionExecutor.tapText]. Returns the node's center bounds, or null
     * if no match.
     *
     * We walk fresh (not against a cached [ScreenContent]) so the result
     * is always current — tapping into stale bounds is the single most
     * common "bridge tapped the wrong thing" bug.
     */
    fun findNodeBoundsByText(rootNode: AccessibilityNodeInfo, needle: String): Bounds? {
        if (needle.isBlank()) return null
        val lowered = needle.lowercase()
        return findFirst(rootNode) { node ->
            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            (text?.contains(lowered) == true) || (desc?.contains(lowered) == true)
        }?.let { found ->
            val r = Rect()
            found.getBoundsInScreen(r)
            r.toBoundsOrZero()
        }
    }

    /**
     * Find the currently-focused input node (for [ActionExecutor.typeText]).
     * Prefers `FOCUS_INPUT` focus, falls back to the first editable node.
     */
    fun findFocusedInput(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return findFirst(rootNode) { it.isEditable }
    }

    private fun findFirst(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findFirst(child, predicate)
            if (hit != null) return hit
            @Suppress("DEPRECATION")
            try { child.recycle() } catch (_: Throwable) { }
        }
        return null
    }

    private fun Rect.toBoundsOrZero(): Bounds =
        Bounds(left = left, top = top, right = right, bottom = bottom)

    private val ZERO_BOUNDS = Bounds(0, 0, 0, 0)
}
