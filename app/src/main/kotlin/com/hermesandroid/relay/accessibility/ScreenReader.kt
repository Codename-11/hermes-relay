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

    // ─── A4: describe_node + stable nodeId lookup ────────────────────────────
    //
    // `findNodeById` re-walks the window tree every call. We deliberately do
    // NOT cache IDs between calls — the UI changes, nodes come and go, and a
    // cached lookup table would be stale the moment the user scrolled. The
    // walker assigns the same `w<windowIndex>:<sequentialIndex>` IDs that the
    // P1 multi-window walker emits, so a nodeId from `read_screen` round-trips
    // cleanly into `describe_node`, `/tap`, and `/scroll` during the same
    // screen dwell.
    //
    // IMPORTANT: the caller takes ownership of the returned node and is
    // responsible for `node.recycle()`. We stop recycling at the match frontier
    // and let it bubble up. `describeNode` below handles this contract.

    /**
     * Walk every window root in [roots] and return the first node whose
     * assigned stable ID matches [nodeId]. Returns `null` if no match.
     *
     * ID format is `w<windowIndex>:<sequentialIndex>` — the same scheme the
     * P1 multi-window `ScreenReader.readAllWindows` walker emits when
     * serializing `ScreenNode.nodeId`. Sequential index is a 0-based pre-order
     * counter that increments for every visited node within that window's
     * traversal (NOT just nodes that end up in the ScreenContent output —
     * we walk the full raw tree so the ID space is stable regardless of
     * interest filtering).
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

        val root = roots[wantedWindow]
        val counter = IntArray(1) // mutable seq counter, boxed so the recursion can mutate it
        return walkForId(root, wantedSeq, counter)
    }

    /**
     * Recursive node-id walker. Returns a non-null match (to be owned by the
     * caller and recycled by them) OR null if this subtree doesn't contain it.
     *
     * Recycling rules:
     *  - Children that don't contain the match are recycled in-place.
     *  - The matched node bubbles up un-recycled — the outermost caller owns
     *    it.
     *  - The root node itself is never recycled here; the caller of
     *    `findNodeById` owns window roots (same contract as `snapshotAllWindows`).
     */
    private fun walkForId(
        node: AccessibilityNodeInfo?,
        wantedSeq: Int,
        counter: IntArray,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val mySeq = counter[0]
        counter[0] = mySeq + 1
        if (mySeq == wantedSeq) {
            // Match at this node. Bubble up without recycling.
            return node
        }
        // If we've already passed the target index without finding it, there's
        // no point descending further — sequential indices are monotonically
        // increasing and children always get higher numbers than their parent.
        if (mySeq > wantedSeq) return null

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val hit = walkForId(child, wantedSeq, counter)
            if (hit != null) {
                // Found the match in this subtree. Don't recycle `child` if
                // it IS the hit (the caller needs it); otherwise the recursive
                // call already handled sibling recycling on its way down.
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
