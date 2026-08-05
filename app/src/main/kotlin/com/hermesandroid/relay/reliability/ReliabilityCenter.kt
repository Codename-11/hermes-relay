package com.hermesandroid.relay.reliability

import android.content.Context
import android.os.Build
import com.hermesandroid.relay.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Android boundary for the local reliability store. Nothing in this object has
 * a network path; writes stay in app-private storage until a user explicitly
 * reviews and shares text through the UI.
 */
object ReliabilityCenter {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hermes-reliability-writer").apply { isDaemon = true }
    }
    private val appSessionId = ReliabilityReport.newId("app")

    @Volatile
    private var store: ReliabilityStore? = null

    fun initialize(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store == null) {
                store = ReliabilityStore(
                    java.io.File(context.applicationContext.filesDir, "reliability/reports-v1.json"),
                )
            }
        }
    }

    fun recordFatal(
        context: Context,
        throwable: Throwable,
        threadName: String,
        timeIso: String = Instant.now().toString(),
    ): ReliabilityReport {
        initialize(context)
        val summary = buildString {
            append(throwable.javaClass.simpleName.ifBlank { "Unexpected crash" })
            throwable.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
        val report = ReliabilityReport(
            reportId = ReliabilityReport.newId(),
            appSessionId = appSessionId,
            timeIso = timeIso,
            kind = ReliabilityKind.FatalCrash,
            owner = ReliabilityOwner.Android,
            severity = ReliabilitySeverity.Fatal,
            summary = summary,
            recovery = "The app restarted. Work already running on Hermes may still be active.",
            reportRecommended = true,
            technicalDetail = "Thread: $threadName\n${stackTraceText(throwable)}",
            environment = environment(),
            pendingReview = true,
        )
        // Fatal capture must complete before the platform terminates the process.
        store?.append(report)
        return report
    }

    fun recordHandled(
        title: String,
        detail: String?,
        throwable: Throwable,
        context: String?,
        routeRole: String? = null,
    ) {
        val target = store ?: return
        val classification = ReliabilityClassifier.classify(throwable, context)
        if (!classification.shouldPersist) return
        val report = ReliabilityReport(
            reportId = ReliabilityReport.newId(),
            appSessionId = appSessionId,
            timeIso = Instant.now().toString(),
            kind = classification.kind,
            owner = classification.owner,
            severity = ReliabilitySeverity.Error,
            summary = title,
            recovery = detail ?: "The failure was handled; retry or review Diagnostics if it continues.",
            reportRecommended = classification.reportRecommended,
            technicalDetail = stackTraceText(throwable),
            routeRole = routeRole,
            environment = environment(),
        )
        writer.execute { runCatching { target.append(report) } }
    }

    fun reports(context: Context): List<ReliabilityReport> {
        initialize(context)
        return store?.readAll().orEmpty()
    }

    fun pendingCrash(context: Context): ReliabilityReport? =
        reports(context).lastOrNull { it.kind == ReliabilityKind.FatalCrash && it.pendingReview }

    fun markReviewed(context: Context, reportId: String) {
        initialize(context)
        store?.markReviewed(reportId)
    }

    fun import(context: Context, report: ReliabilityReport) {
        initialize(context)
        store?.append(report)
    }

    fun environment(): ReliabilityEnvironment = ReliabilityEnvironment(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        flavor = BuildConfig.FLAVOR,
        manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "?" },
        model = Build.MODEL.orEmpty().ifBlank { "?" },
        androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "?" },
        sdkInt = Build.VERSION.SDK_INT,
    )

    private fun stackTraceText(throwable: Throwable): String =
        StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString().trim()
}
