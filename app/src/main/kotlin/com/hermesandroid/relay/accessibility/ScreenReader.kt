package com.hermesandroid.relay.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 3 — accessibility `accessibility-runtime`
 *
 * Walks the `AccessibilityNodeInfo` trees of every active accessibility
 * window and produces a single structured, serializable snapshot the agent
 * can reason about.
 *
 * The output shape is deliberately flat: the agent overwhelmingly cares
 * about *"what text can I see, and where is it?"* — not the exact widget
 * hierarchy. We emit one [ScreenNode] per interesting node (anything with
 * non-blank text, content description, or a click / long-click action) and
 * include its screen bounds so [ActionExecutor.tapText] can hand them to
 * `dispatchGesture` without re-walking the tree.
 *
 * # Multi-window walk (P1)
 *
 * As of P1 (`feature/P1-all-windows`) the reader walks *every* live
 * accessibility window, not just `rootInActiveWindow`. This catches system
 * overlays, popup menus, the notification shade when pulled down,
 * permission dialogs, and multi-window split-screen state — all of which
 * were previously invisible to the agent.
 *
 * ## Tree-merging strategy
 *
 * We flatten all windows into **one** `ScreenContent` rather than returning
 * per-window trees. This is deliberate: the existing public contract
 * (consumed by `BridgeCommandHandler` and the Python-side LLM prompt) is
 * "one JSON object with a `nodes[]` array". Preserving that contract means
 * no wire-format change and no consumer refactor.
 *
 * To disambiguate nodes that belong to different windows we prefix every
 * `nodeId` with `w<windowIndex>:<sequentialIndex>`. `windowIndex` is the
 * position in `service.windows` (top-of-stack first, per Android docs),
 * `sequentialIndex` is the 0-based collection order within the combined
 * walk. The root bounds emitted on `ScreenContent.rootBounds` are the
 * **union** of every window's root bounds, so geometric callers still see
 * a single enclosing rectangle.
 *
 * ## MAX_NODES cap
 *
 * The 512-node cap applies to the *combined* tree, not per-window. If the
 * first window alone exceeds the cap we short-circuit the whole walk and
 * set `truncated = true` without descending into later windows — the agent
 * already has more than it can reasonably use.
 *
 * ## Node recycling landmine
 *
 * Every `AccessibilityWindowInfo.getRoot()` returns a **fresh**
 * `AccessibilityNodeInfo` that must be recycled by the caller. Every
 * `info.getChild(i)` also returns a fresh ref. The public methods here
 * do NOT recycle their input roots — the caller
 * ([HermesAccessibilityService.snapshotAllWindows] in practice) owns the
 * lifetime of the roots it produced. Child nodes fetched during the walk
 * are recycled per-iteration in `try/finally`.
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
         * wire payload under ~40 KB in the worst case. With P1 the cap
         * now spans the *combined* tree across all live windows.
         */
        const val MAX_NODES = 512

        /** Hard cap on individual text-field length before truncation. */
        const val MAX_TEXT_LEN = 2000
    }

    /**
     * Structured representation of a screen, ready for JSON serialization.
     * The [rootBounds] are in absolute screen pixels (what `dispatchGesture`
     * uses). When P1 merges multiple windows, [rootBounds] is the union of
     * every window's root bounds.
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
         * Stable, walk-scoped identifier for this node. Format:
         * `w<windowIndex>:<sequentialIndex>` — e.g. `w0:42` for the 43rd
         * emitted node from the top-of-stack window, `w1:7` for the 8th
         * emitted node of the next window down. Always present when the
         * node was produced by [readAllWindows]; may be null for callers
         * that bypass the multi-window entry point (legacy tests).
         *
         * Also re-used by A3 `searchNodes` so filtered results feed back
         * into `tap nodeId` and other node-ID-addressable commands.
         *
         * The Python `android_tool` layer has long advertised a `nodeId`
         * field in its doc-comments; P1 is the first version that actually
         * emits it on the wire.
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
     * Back-compat single-root entry point. Delegates to [readAllWindows]
     * with a single-element list so the walk semantics (cap, recycling,
     * node-id prefix) stay identical regardless of which public method
     * the caller picked.
     *
     * This method does NOT recycle [rootNode] — the caller owns it.
     *
     * @param includeBounds when false, we still collect bounds for
     *        traversal decisions but zero them in the output to shrink the
     *        payload. The agent almost always wants bounds so this defaults
     *        true.
     */
    fun readScreen(
        rootNode: AccessibilityNodeInfo,
        includeBounds: Boolean = true,
    ): ScreenContent = readAllWindows(listOf(rootNode), includeBounds)

    /**
     * P1 multi-window entry point. Walks every root in [windowRoots] in
     * order (top-of-stack first) and emits a single flat [ScreenContent]
     * with node IDs prefixed by window index.
     *
     * The [windowRoots] list is NOT recycled by this method — callers
     * ([HermesAccessibilityService.snapshotAllWindows] + finally blocks
     * in `BridgeCommandHandler`) own the roots they produced. Child nodes
     * fetched during traversal ARE recycled per-iteration.
     *
     * @param includeBounds see [readScreen].
     */
    fun readAllWindows(
        windowRoots: List<AccessibilityNodeInfo>,
        includeBounds: Boolean = true,
    ): ScreenContent {
        val collected = ArrayList<ScreenNode>(128)
        var truncated = false

        // Union of every window's root bounds. Starts empty and absorbs
        // each window's root rect via Rect.union(), so one-window callers
        // see identical output to the pre-P1 code path.
        val unionRect = Rect()
        var unionInitialized = false
        var packageName: String? = null

        for ((windowIndex, rootNode) in windowRoots.withIndex()) {
            if (collected.size >= MAX_NODES) {
                // Cap already exhausted by an earlier window — do not
                // descend. This is intentional: the agent has more than
                // it can reason about and we'd rather be honest about it
                // than silently clip the later-window content.
                truncated = true
                break
            }

            // Latch the first window's package name as the "primary" one.
            // Multi-window state with different packages (e.g. a popup from
            // a different process) is rare, and the agent can still see
            // the full package-name string on each node via className if
            // it needs finer-grained attribution.
            if (packageName == null) {
                packageName = rootNode.packageName?.toString()
            }

            val rootRect = Rect()
            rootNode.getBoundsInScreen(rootRect)
            if (!unionInitialized) {
                unionRect.set(rootRect)
                unionInitialized = true
            } else {
                unionRect.union(rootRect)
            }

            val hit = walk(
                node = rootNode,
                windowIndex = windowIndex,
                out = collected,
                includeBounds = includeBounds,
            )
            if (hit) {
                truncated = true
                break
            }
        }

        val rootBounds = if (unionInitialized) unionRect.toBoundsOrZero() else ZERO_BOUNDS

        return ScreenContent(
            packageName = packageName,
            rootBounds = rootBounds,
            nodes = collected,
            truncated = truncated,
        )
    }

    /**
     * Recursive walker. Returns `true` if the cap was hit (signaling
     * caller to mark output as truncated).
     *
     * [windowIndex] is the position in the parent walk's `windowRoots`
     * list and becomes the `w<n>:` prefix on every emitted node ID.
     */
    private fun walk(
        node: AccessibilityNodeInfo?,
        windowIndex: Int,
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
            // Node ID is `w<windowIndex>:<sequentialIndex>`. Using the
            // sequential emission index (not a hash of the node) keeps
            // IDs stable within a single snapshot and compact on the
            // wire, at the cost of being snapshot-scoped (not durable
            // across successive /screen calls). That's the right tradeoff
            // — the agent re-reads the screen before each action anyway.
            val nodeId = "w$windowIndex:${out.size}"
            out.add(
                ScreenNode(
                    nodeId = nodeId,
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
                val hit = walk(child, windowIndex, out, includeBounds)
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
        // H2 fix: hoist the emission-id counter OUTSIDE the per-window loop
        // so it mirrors `walk`/`findNodeById`'s GLOBAL "interesting" counter
        // exactly. Previously this was scoped to each window, producing IDs
        // like `w1:0` when the canonical scheme is `w1:N` (N = global). Round
        // trips through `find_nodes → tap nodeId` then resolved to the wrong
        // node on any screen with >1 window.
        val nextIndex = intArrayOf(0)

        for ((windowIndex, root) in roots.withIndex()) {
            if (out.size >= effectiveLimit) break
            if (visited[0] >= MAX_NODES) break
            searchWalk(
                node = root,
                windowIndex = windowIndex,
                nextIndex = nextIndex,
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

        val nodeText = node.text?.toString()?.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LEN)
        val contentDesc = node.contentDescription?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_TEXT_LEN)
        val nodeClassName = node.className?.toString()
        val nodeClickable = node.isClickable
        val nodeLongClickable = node.isLongClickable
        val nodeScrollable = node.isScrollable
        val nodeEditable = node.isEditable

        // H2 fix: nextIndex must mirror `walk`/`findNodeById`'s emission
        // counter exactly — increment ONLY for nodes that pass the canonical
        // "interesting" predicate (text || contentDesc || clickable ||
        // longClickable || scrollable || editable, with non-empty bounds),
        // not for every visited node. Otherwise the IDs handed back from
        // find_nodes drift relative to readAllWindows + findNodeById and
        // round-trip lookups silently resolve to the wrong node.
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = rect.toBoundsOrZero()
        val canonicalInteresting = (nodeText != null || contentDesc != null ||
            nodeClickable || nodeLongClickable || nodeScrollable || nodeEditable) &&
            !bounds.isEmpty

        val thisNodeIndex: Int
        if (canonicalInteresting) {
            thisNodeIndex = nextIndex[0]
            nextIndex[0] += 1
        } else {
            thisNodeIndex = -1
        }

        val matchesText = loweredText == null ||
            (nodeText?.lowercase()?.contains(loweredText) == true) ||
            (contentDesc?.lowercase()?.contains(loweredText) == true)
        val matchesClass = className == null || nodeClassName == className
        val matchesClickable = clickable == null || nodeClickable == clickable

        // Only emit nodes that BOTH match the search filter AND are
        // canonically-interesting (i.e. would also be emitted by `walk`).
        // Restricting emission to canonical nodes is what makes the round
        // trip find_nodes → tap nodeId reliable.
        if (canonicalInteresting && matchesText && matchesClass && matchesClickable) {
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
     * Find the first node across [windowRoots] whose text or
     * content-description contains [needle] (case-insensitive). Used by
     * [ActionExecutor.tapText]. Returns the node's bounds, or null if no
     * match anywhere.
     *
     * We walk fresh (not against a cached [ScreenContent]) so the result
     * is always current — tapping into stale bounds is the single most
     * common "bridge tapped the wrong thing" bug.
     */
    fun findNodeBoundsByText(
        windowRoots: List<AccessibilityNodeInfo>,
        needle: String,
    ): Bounds? {
        if (needle.isBlank()) return null
        val lowered = needle.lowercase()
        for (root in windowRoots) {
            val hit = findFirst(root) { node ->
                val text = node.text?.toString()?.lowercase()
                val desc = node.contentDescription?.toString()?.lowercase()
                (text?.contains(lowered) == true) || (desc?.contains(lowered) == true)
            }
            if (hit != null) {
                val r = Rect()
                hit.getBoundsInScreen(r)
                return r.toBoundsOrZero()
            }
        }
        return null
    }

    /**
     * Back-compat single-root overload. Delegates to the multi-window
     * form so tests and any legacy call site still compile unchanged.
     */
    fun findNodeBoundsByText(rootNode: AccessibilityNodeInfo, needle: String): Bounds? =
        findNodeBoundsByText(listOf(rootNode), needle)

    /**
     * Find the currently-focused input node across [windowRoots] (for
     * [ActionExecutor.typeText]). Prefers `FOCUS_INPUT` focus on each
     * window in order, falls back to the first editable node found in any
     * window.
     *
     * Returned node is caller-owned — must be `recycle()`d on API < 33.
     */
    fun findFocusedInput(windowRoots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        for (root in windowRoots) {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        for (root in windowRoots) {
            findFirst(root) { it.isEditable }?.let { return it }
        }
        return null
    }

    /**
     * Back-compat single-root overload. Delegates to the multi-window
     * form.
     */
    fun findFocusedInput(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFocusedInput(listOf(rootNode))

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

    // ─── A4: describe_node + stable nodeId lookup ────────────────────────────
    //
    // `findNodeById` re-walks the window tree every call. We deliberately do
    // NOT cache IDs between calls — the UI changes, nodes come and go, and a
    // cached lookup table would be stale the moment the user scrolled. The
    // walker assigns the same `w<windowIndex>:<sequentialIndex>` IDs that the
    // P1 multi-window [walk] emits, so a nodeId from `read_screen` round-trips
    // cleanly into `describe_node`, `/tap`, and `/scroll` during the same
    // screen dwell.
    //
    // IMPORTANT: the counter MUST mirror P1 semantics exactly:
    //   1. Only "interesting" nodes (text/contentDesc/clickable/longClickable/
    //      scrollable/editable AND non-empty bounds) get an ID.
    //   2. The sequential index is a GLOBAL pre-order emission counter
    //      shared across all windows (not per-window). Window 0 emits a
    //      handful of nodes at 0..N-1, then window 1's first emission is
    //      N, not 0. This matches how readAllWindows feeds a single
    //      `collected` list into multiple `walk()` calls.
    //
    // IMPORTANT: the caller takes ownership of the returned node and is
    // responsible for `node.recycle()`. We stop recycling at the match frontier
    // and let it bubble up. `describeNode` below handles this contract.

    /**
     * Walk every window root in [roots] and return the first node whose
     * assigned stable ID matches [nodeId]. Returns `null` if no match.
     *
     * ID format is `w<windowIndex>:<sequentialIndex>` — the same scheme the
     * P1 multi-window [walk] emits on [ScreenNode.nodeId]. The sequential
     * index is a 0-based GLOBAL emission counter (shared across windows)
     * that increments only for "interesting" nodes — matching the P1 filter
     * in [walk] (`interesting = has text/desc/clickable/longClickable/
     * scrollable/editable AND non-empty bounds`).
     *
     * The caller takes ownership of the returned [AccessibilityNodeInfo] and
     * MUST recycle it (on API <= 33) when done. We stop recycling at the
     * match frontier so the node survives the return trip.
     */
    fun findNodeById(
        roots: List<AccessibilityNodeInfo>,
        nodeId: String,
    ): AccessibilityNodeInfo? {
        if (nodeId.isBlank()) return null
        // Parse `w<windowIdx>:<seqIdx>`. Reject malformed IDs up front so we
        // don't spend O(tree) walking when the input can't possibly match.
        val colonIdx = nodeId.indexOf(':')
        if (colonIdx <= 1 || nodeId[0] != 'w') return null
        val wantedWindow = nodeId.substring(1, colonIdx).toIntOrNull() ?: return null
        val wantedSeq = nodeId.substring(colonIdx + 1).toIntOrNull() ?: return null
        if (wantedWindow < 0 || wantedWindow >= roots.size || wantedSeq < 0) return null

        // GLOBAL counter, shared across every window's walk to match
        // readAllWindows' shared `collected` list ordering.
        val counter = IntArray(1)
        for ((windowIndex, root) in roots.withIndex()) {
            // Cheap pre-filter: the wantedSeq is global, so we still have to
            // walk earlier windows to drain their interesting-node emission
            // counts, BUT once we're at or past the wanted window we can
            // look for the match. We only RETURN a match when we're on the
            // correct windowIndex AND the global counter reaches wantedSeq.
            val hit = walkForId(root, wantedSeq, counter, windowIndex, wantedWindow)
            if (hit != null) return hit
            if (counter[0] > wantedSeq) return null
        }
        return null
    }

    /**
     * Recursive node-id walker. Increments [counter] only for "interesting"
     * nodes (matching P1's [walk] semantics). Returns a non-null match
     * (caller-owned and responsible for recycling) OR null if this subtree
     * doesn't contain it.
     *
     * [currentWindow] is the index of the window currently being walked;
     * [wantedWindow] is the window portion of the parsed nodeId. We only
     * return a hit when they match — earlier and later windows still
     * contribute to the global counter but can't claim the match.
     *
     * Recycling rules:
     *  - Children that don't contain the match are recycled in-place.
     *  - The matched node bubbles up un-recycled — the outermost caller owns it.
     *  - The root node itself is never recycled here; the caller of
     *    `findNodeById` owns window roots (same contract as `snapshotAllWindows`).
     */
    private fun walkForId(
        node: AccessibilityNodeInfo?,
        wantedSeq: Int,
        counter: IntArray,
        currentWindow: Int,
        wantedWindow: Int,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (counter[0] > wantedSeq) return null

        // Determine whether this node would be emitted by P1's [walk].
        // Must mirror the `interesting` predicate there exactly or the
        // counter drifts and the round-trip breaks.
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = rect.toBoundsOrZero()
        val nodeText = node.text?.toString()?.takeIf { it.isNotBlank() }
        val contentDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val clickable = node.isClickable
        val longClickable = node.isLongClickable
        val scrollable = node.isScrollable
        val interesting = (nodeText != null || contentDesc != null ||
            clickable || longClickable || scrollable || node.isEditable) &&
            !bounds.isEmpty

        if (interesting) {
            val mySeq = counter[0]
            counter[0] = mySeq + 1
            if (currentWindow == wantedWindow && mySeq == wantedSeq) {
                // Match. Bubble up without recycling.
                return node
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (counter[0] > wantedSeq) return null
            val child = node.getChild(i) ?: continue
            val hit = walkForId(child, wantedSeq, counter, currentWindow, wantedWindow)
            if (hit != null) {
                // Match in this subtree — don't recycle the hit.
                return hit
            }
            @Suppress("DEPRECATION")
            try { child.recycle() } catch (_: Throwable) { }
        }
        return null
    }

    /**
     * A4: result of a `describe_node` lookup. Serialized directly into the
     * `bridge.response` result payload by [BridgeCommandHandler].
     *
     * When [found] is false, [properties] is null and [error] explains why.
     */
    data class DescribeNodeResult(
        val found: Boolean,
        val properties: JsonObject? = null,
        val error: String? = null,
    )

    /**
     * A4: return the full property bag for [nodeId] on the current window
     * set. Props: `nodeId`, `bounds`, `className`, `text`, `contentDescription`,
     * `hintText` (API 26+), `viewIdResourceName`, `childCount`, plus a dozen
     * state flags. `checked` is null when the node isn't checkable so callers
     * can distinguish "not a toggle" from "unchecked toggle".
     *
     * Walks the tree via [findNodeById], builds the JSON, and recycles the
     * resolved node before returning.
     */
    fun describeNode(
        roots: List<AccessibilityNodeInfo>,
        nodeId: String,
    ): DescribeNodeResult {
        val node = findNodeById(roots, nodeId)
            ?: return DescribeNodeResult(found = false, error = "node not found: $nodeId")

        try {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val bounds = rect.toBoundsOrZero()

            // hintText is API 26+. minSdk on this project is 26, so in practice
            // it's always available — but we guard anyway to keep the property
            // out of the payload on devices where the API call would throw.
            val hintText: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()
            } else null

            // Local helper to collapse blank/null strings to JsonNull. A
            // local lambda rather than an extension `put` overload so we
            // don't shadow `JsonObjectBuilder.put(String, String?)`.
            fun strOrNull(raw: String?): JsonElement =
                if (raw.isNullOrBlank()) JsonNull else JsonPrimitive(raw)

            val props: JsonObject = buildJsonObject {
                put("nodeId", nodeId)
                put("bounds", buildJsonObject {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                    put("centerX", bounds.centerX)
                    put("centerY", bounds.centerY)
                    put("width", bounds.width)
                    put("height", bounds.height)
                })
                put("className", strOrNull(node.className?.toString()))
                put("text", strOrNull(node.text?.toString()))
                put("contentDescription", strOrNull(node.contentDescription?.toString()))
                put("hintText", strOrNull(hintText))
                put("viewIdResourceName", strOrNull(node.viewIdResourceName))
                put("childCount", node.childCount)
                put("clickable", node.isClickable)
                put("longClickable", node.isLongClickable)
                put("focusable", node.isFocusable)
                put("focused", node.isFocused)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("checkable", node.isCheckable)
                // Null vs false is load-bearing: null = "not a toggle",
                // false = "unchecked toggle".
                put("checked", if (node.isCheckable) JsonPrimitive(node.isChecked) else JsonNull)
                put("enabled", node.isEnabled)
                put("selected", node.isSelected)
                put("password", node.isPassword)
            }

            return DescribeNodeResult(found = true, properties = props)
        } finally {
            @Suppress("DEPRECATION")
            try { node.recycle() } catch (_: Throwable) { }
        }
    }

    private fun Rect.toBoundsOrZero(): Bounds =
        Bounds(left = left, top = top, right = right, bottom = bottom)

    private val ZERO_BOUNDS = Bounds(0, 0, 0, 0)
}
