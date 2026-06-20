package com.hermesandroid.relay.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.hermesandroid.relay.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

/**
 * Lightweight, privacy-respecting crash capture — no Firebase/Crashlytics, no
 * network, no third-party SDK.
 *
 * On a fatal uncaught exception we persist a structured report to the app's
 * private storage, then **re-raise to the platform's previous handler** so the
 * system "app stopped" dialog still shows and Google Play Android vitals still
 * records the crash. We only observe; we never swallow.
 *
 * On the next launch [CrashReportGate] reads the pending report and offers the
 * user a clean copy / "report on GitHub" flow (see [CrashReportDialog]). The
 * GitHub path pre-fills our `bug_report.yml` issue form so a one-star "it keeps
 * crashing" review can become an actionable issue with a stack trace attached.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR = "crash"
    private const val FILE = "last-crash.json"

    /** Public issue tracker — keep in sync with the git remote. */
    private const val GITHUB_NEW_ISSUE =
        "https://github.com/Codename-11/hermes-relay/issues/new"

    /**
     * Cap the stack trace we inline into the GitHub URL. Browsers + GitHub
     * truncate very long URLs, so we ship the head of the trace in the form and
     * copy the *full* report to the clipboard for the user to paste if needed.
     */
    private const val MAX_TRACE_FOR_URL = 3000

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    @Volatile
    private var installed = false

    /**
     * Install the process-wide uncaught-exception handler. Idempotent; call as
     * early as possible in [com.hermesandroid.relay.HermesRelayApp.onCreate] so
     * crashes during the rest of app init are captured too.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persist(appContext, thread, throwable)
            } catch (t: Throwable) {
                // Never let the reporter itself worsen the crash.
                Log.e(TAG, "Failed to persist crash report", t)
            }
            // Re-raise so the platform behaves exactly as it would without us:
            // system dialog + Play vitals collection.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun persist(context: Context, thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString().trim()
        val report = CrashReport(
            timeIso = isoNow(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "?" },
            model = Build.MODEL.orEmpty().ifBlank { "?" },
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "?" },
            sdkInt = Build.VERSION.SDK_INT,
            threadName = thread.name.orEmpty().ifBlank { "?" },
            exceptionSummary = "${throwable.javaClass.name}: ${throwable.message.orEmpty()}".trim(),
            stackTrace = trace,
        )
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, FILE).writeText(json.encodeToString(report))
    }

    /** Read the pending report without clearing it. */
    fun peekPending(context: Context): CrashReport? = readFile(context)

    /** Read the pending report and delete it (show-once semantics). */
    fun consumePending(context: Context): CrashReport? {
        val report = readFile(context)
        clearPending(context)
        return report
    }

    fun clearPending(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    private fun readFile(context: Context): CrashReport? = runCatching {
        val file = reportFile(context)
        if (!file.exists()) return null
        json.decodeFromString<CrashReport>(file.readText())
    }.getOrNull()

    private fun reportFile(context: Context): File =
        File(File(context.filesDir, DIR), FILE)

    /**
     * Build a pre-filled GitHub "new issue" URL.
     *
     * Uses the **stable** classic `title` + `body` + `labels` query params, NOT
     * issue-form field-`id` prefilling (`template=...&<id>=...`). The latter is
     * a GitHub public-preview feature and was observed to silently not apply
     * (only `title` carried), which is unacceptable for a crash reporter that
     * fires on devices we can't retry from. `blank_issues_enabled: true` in
     * `.github/ISSUE_TEMPLATE/config.yml` guarantees `?body=` opens a prefilled
     * issue. The body mirrors `bug_report.yml`'s sections in markdown so triage
     * structure is preserved without depending on the preview path.
     */
    fun buildGithubIssueUrl(report: CrashReport): String {
        // LinkedHashMap preserves a stable, readable param order.
        val params = linkedMapOf(
            "title" to "[Bug]: Crash — ${report.shortTitle()}",
            "labels" to "bug",
            "body" to buildIssueBody(report),
        )
        return GITHUB_NEW_ISSUE + "?" + params.entries.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        }
    }

    private fun buildIssueBody(report: CrashReport): String {
        val trace = report.stackTrace.let {
            if (it.length > MAX_TRACE_FOR_URL) {
                it.take(MAX_TRACE_FOR_URL) + "\n… (truncated — full report copied to your clipboard)"
            } else {
                it
            }
        }
        return buildString {
            appendLine(
                "> ⚠️ Before submitting: remove any secrets, tokens, real hostnames/IPs, " +
                    "or personal data from the trace below.",
            )
            appendLine()
            appendLine("### Affected area")
            appendLine("Android app")
            appendLine()
            appendLine("### What happened?")
            appendLine("The app closed unexpectedly. Auto-captured crash report below.")
            appendLine()
            appendLine("### Environment")
            appendLine(report.environmentBlock())
            appendLine()
            appendLine("### Crash")
            appendLine("```")
            appendLine(trace)
            appendLine("```")
            appendLine()
            append("<sub>Captured by the Hermes-Relay in-app crash reporter · ${report.timeIso}</sub>")
        }
    }

    private fun isoNow(): String = runCatching {
        OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString()
    }.getOrDefault(java.util.Date().toString())
}

/**
 * Structured, serializable crash snapshot. Persisted as JSON between the
 * crashing session and the next launch.
 */
@Serializable
data class CrashReport(
    val timeIso: String,
    val versionName: String,
    val versionCode: Int,
    val flavor: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val threadName: String,
    val exceptionSummary: String,
    val stackTrace: String,
) {
    fun deviceLine(): String = "$manufacturer $model"

    fun androidLine(): String = "Android $androidRelease (SDK $sdkInt)"

    fun versionLine(): String = "$versionName (code $versionCode) $flavor"

    /** A short, human title for the GitHub issue — class name + trimmed message. */
    fun shortTitle(): String {
        val firstLine = exceptionSummary.lineSequence().firstOrNull().orEmpty()
        val simpleClass = firstLine.substringBefore(':').substringAfterLast('.').ifBlank { "crash" }
        val message = firstLine.substringAfter(':', "").trim()
        return (if (message.isBlank()) simpleClass else "$simpleClass: $message").take(90)
    }

    /** Full, copy-paste-ready report shown in the dialog and copied to clipboard. */
    fun toPlainText(): String = buildString {
        appendLine("Hermes-Relay crash report")
        appendLine("Time:    $timeIso")
        appendLine("App:     ${versionLine()}")
        appendLine("Device:  ${deviceLine()}")
        appendLine("Android: ${androidLine()}")
        appendLine("Thread:  $threadName")
        appendLine()
        append(stackTrace)
    }

    /** Matches the `environment` textarea default in `bug_report.yml`. */
    fun environmentBlock(): String = buildString {
        appendLine("- Hermes-Relay version/tag: $versionName (code $versionCode)")
        appendLine(
            "- Install surface: " +
                if (flavor.equals("sideload", ignoreCase = true)) "sideload APK" else "Google Play",
        )
        appendLine("- Android device and OS: ${deviceLine()} — ${androidLine()}")
        append("- Connection mode: LAN / Tailscale / public TLS / other")
    }
}
