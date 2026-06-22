package com.hermesandroid.relay.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * One implementation of the three user-initiated outbound paths shared by the
 * crash reporter and the diagnostics detail view: build a pre-filled GitHub
 * "new issue" URL, hand text to the system share sheet, and copy text to the
 * clipboard.
 *
 * Nothing here transmits automatically — every path is triggered by an explicit
 * user tap, and the share/clipboard paths never leave the device until the user
 * chooses a destination. Both [CrashReporter]/[com.hermesandroid.relay.ui.components.CrashReportGate]
 * and the diagnostics detail dialog call these so the GitHub URL shape, share
 * intent, and clipboard label stay identical across the app.
 */
object IssueReport {

    /** Public issue tracker — keep in sync with the git remote. */
    private const val GITHUB_NEW_ISSUE =
        "https://github.com/Codename-11/hermes-relay/issues/new"

    /** Clipboard label used for every report copy in the app. */
    const val CLIP_LABEL = "Hermes-Relay report"

    /**
     * Build a pre-filled GitHub "new issue" URL from a generic title/body/labels
     * triple.
     *
     * Uses the **stable** classic `title` + `body` + `labels` query params, NOT
     * issue-form field-`id` prefilling (`template=...&<id>=...`). The latter is a
     * GitHub public-preview feature that was observed to silently not apply (only
     * `title` carried), which is unacceptable for a reporter that fires on devices
     * we can't retry from. `blank_issues_enabled: true` in
     * `.github/ISSUE_TEMPLATE/config.yml` guarantees `?body=` opens a prefilled
     * issue.
     *
     * @param labels comma-separated GitHub labels (e.g. "bug"); omitted from the
     *               query when blank.
     */
    fun buildGithubIssueUrl(
        title: String,
        bodyMarkdown: String,
        labels: String = "bug",
    ): String {
        // LinkedHashMap preserves a stable, readable param order.
        val params = linkedMapOf("title" to title)
        if (labels.isNotBlank()) params["labels"] = labels
        params["body"] = bodyMarkdown
        return GITHUB_NEW_ISSUE + "?" + params.entries.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }
    }

    /** Copy [text] to the system clipboard under the shared report label. */
    fun copyToClipboard(context: Context, text: String, label: String = CLIP_LABEL) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    /**
     * Offer [text] to the system share sheet (email, chat, notes, Drive…). The
     * user picks the destination, so nothing leaves the device until they choose
     * to send it — same privacy posture as [copyToClipboard]. Returns false if no
     * app can handle a plain-text share so callers can fall back to clipboard.
     */
    fun share(context: Context, subject: String, text: String, chooserTitle: String = "Share report"): Boolean =
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(
                Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)

    /** Open [url] in the user's browser. Returns false if nothing can handle it. */
    fun openUrl(context: Context, url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
