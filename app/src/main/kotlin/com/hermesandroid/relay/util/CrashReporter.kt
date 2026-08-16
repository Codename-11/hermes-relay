package com.hermesandroid.relay.util

import android.content.Context
import android.os.Process
import android.util.Log
import com.hermesandroid.relay.reliability.ReliabilityCenter
import com.hermesandroid.relay.reliability.LegacyCrashSnapshot
import com.hermesandroid.relay.reliability.ReliabilityRedactor
import com.hermesandroid.relay.reliability.ReliabilityReport
import com.hermesandroid.relay.reliability.migrateLegacyCrash
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/** Local-only fatal capture. The platform handler still owns termination and Play vitals. */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val LEGACY_DIR = "crash"
    private const val LEGACY_FILE = "last-crash.json"
    private const val MAX_TRACE_FOR_URL = 3_000
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var installed = false

    fun install(context: Context) {
        ReliabilityCenter.initialize(context)
        migrateLegacy(context)
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ReliabilityCenter.recordFatal(appContext, throwable, thread.name.orEmpty().ifBlank { "?" })
            } catch (captureFailure: Throwable) {
                // Never let the reporter worsen or replace the original crash.
                Log.e(TAG, "Failed to persist local crash report", captureFailure)
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun peekPending(context: Context): ReliabilityReport? {
        migrateLegacy(context)
        return ReliabilityCenter.pendingCrash(context)
    }

    fun clearPending(context: Context, reportId: String? = null) {
        val target = reportId ?: peekPending(context)?.reportId ?: return
        ReliabilityCenter.markReviewed(context, target)
    }

    fun buildGithubIssueUrl(report: ReliabilityReport): String = IssueReport.buildGithubIssueUrl(
        title = "[Bug]: Android crash — ${ReliabilityRedactor.redact(report.shortTitle(), 90)}",
        bodyMarkdown = buildIssueBody(report),
        labels = "bug,area:android",
    )

    private fun buildIssueBody(report: ReliabilityReport): String {
        val trace = report.technicalDetail.orEmpty().let {
            if (it.length > MAX_TRACE_FOR_URL) {
                it.take(MAX_TRACE_FOR_URL) + "\n… (truncated — review/copy the local report for the remainder)"
            } else {
                it
            }
        }
        return ReliabilityRedactor.redact(
            buildString {
                appendLine("> No report was uploaded automatically. This is the locally reviewed, redacted copy.")
                appendLine()
                appendLine("### Affected area")
                appendLine("Android app")
                appendLine()
                appendLine("### What happened?")
                appendLine(report.summary)
                appendLine()
                appendLine("### Recovery")
                appendLine(report.recovery)
                appendLine()
                appendLine("### Environment")
                appendLine(report.environmentBlock())
                appendLine("- Report ID: ${report.reportId}")
                appendLine("- App session ID: ${report.appSessionId}")
                appendLine()
                appendLine("### Redacted technical detail")
                appendLine("```")
                appendLine(trace)
                appendLine("```")
                appendLine()
                append("<sub>Captured locally by Hermes-Relay · ${report.timeIso}</sub>")
            },
            maxLength = 12_000,
        )
    }

    /** Import the pre-v1 one-file crash format once, redacting before the new store sees it. */
    private fun migrateLegacy(context: Context) {
        val file = File(File(context.filesDir, LEGACY_DIR), LEGACY_FILE)
        if (!file.isFile) return
        runCatching {
            val old = json.decodeFromString<LegacyCrashSnapshot>(file.readText())
            val report = migrateLegacyCrash(old)
            ReliabilityCenter.import(context, report)
            file.delete()
        }.onFailure {
            Log.w(TAG, "Legacy crash report could not be migrated", it)
        }
    }
}
