package com.hermesandroid.relay.reliability

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

const val RELIABILITY_SCHEMA_VERSION = 1

@Serializable
enum class ReliabilityKind {
    FatalCrash,
    AnrSignal,
    SessionCheckpoint,
    RecoverableProductError,
    Connectivity,
    Authentication,
    RateLimit,
    ServiceUnavailable,
    ExpectedCancellation,
    UserDenial,
}

@Serializable
enum class ReliabilityOwner(val label: String) {
    Android("Android"),
    Dashboard("Dashboard"),
    Api("API"),
    Relay("Relay"),
    UpstreamGateway("Upstream Gateway"),
    Voice("Voice"),
    Unknown("Unknown"),
}

@Serializable
enum class ReliabilitySeverity { Info, Warning, Error, Fatal }

@Serializable
data class ReliabilityEnvironment(
    val versionName: String,
    val versionCode: Int,
    val flavor: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
)

/**
 * Allowlisted local reliability record. There are deliberately no fields for
 * prompts, messages, profile names, product session IDs, URLs, media, or paths.
 */
@Serializable
data class ReliabilityReport(
    val schemaVersion: Int = RELIABILITY_SCHEMA_VERSION,
    val reportId: String,
    val appSessionId: String,
    val timeIso: String,
    val kind: ReliabilityKind,
    val owner: ReliabilityOwner,
    val severity: ReliabilitySeverity,
    val summary: String,
    val recovery: String,
    val reportRecommended: Boolean,
    val technicalDetail: String? = null,
    val routeRole: String? = null,
    val environment: ReliabilityEnvironment,
    val pendingReview: Boolean = false,
) {
    fun shortTitle(): String = summary.lineSequence().firstOrNull().orEmpty().ifBlank {
        kind.name
    }.take(90)

    fun versionLine(): String =
        "${environment.versionName} (code ${environment.versionCode}) ${environment.flavor}"

    fun environmentBlock(): String = buildString {
        appendLine("- Hermes-Relay version/tag: ${environment.versionName} (code ${environment.versionCode})")
        appendLine(
            "- Install surface: " +
                if (environment.flavor.equals("sideload", ignoreCase = true)) "sideload APK" else "Google Play",
        )
        appendLine(
            "- Android device and OS: ${environment.manufacturer} ${environment.model} — " +
                "Android ${environment.androidRelease} (SDK ${environment.sdkInt})",
        )
        append("- Connection mode: ${routeRole ?: "unknown"}")
    }

    /** Exact local review/copy/share payload. Redaction is repeated for legacy defense in depth. */
    fun toPlainText(): String = ReliabilityRedactor.redact(
        buildString {
            appendLine("Hermes-Relay support information")
            appendLine("Report:  $reportId")
            appendLine("Session: $appSessionId")
            appendLine("Time:    $timeIso")
            appendLine("Type:    ${kind.name}")
            appendLine("Owner:   ${owner.label}")
            appendLine("App:     ${versionLine()}")
            appendLine(
                "Device:  ${environment.manufacturer} ${environment.model} — " +
                    "Android ${environment.androidRelease} (SDK ${environment.sdkInt})",
            )
            routeRole?.let { appendLine("Route:   $it") }
            appendLine()
            appendLine("What happened: $summary")
            appendLine("Recovery:      $recovery")
            technicalDetail?.let {
                appendLine()
                appendLine("Technical detail (redacted)")
                append(it)
            }
        },
    )

    companion object {
        fun newId(prefix: String = "rpt"): String =
            "$prefix-${UUID.randomUUID().toString().replace("-", "").take(16)}"
    }
}

/** Old `files/crash/last-crash.json` shape, retained only for one-way migration. */
@Serializable
data class LegacyCrashSnapshot(
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
)

fun migrateLegacyCrash(
    old: LegacyCrashSnapshot,
    reportId: String = ReliabilityReport.newId(),
    appSessionId: String = ReliabilityReport.newId("legacy"),
): ReliabilityReport = ReliabilityReport(
    reportId = reportId,
    appSessionId = appSessionId,
    timeIso = runCatching { Instant.parse(old.timeIso).toString() }.getOrDefault(old.timeIso),
    kind = ReliabilityKind.FatalCrash,
    owner = ReliabilityOwner.Android,
    severity = ReliabilitySeverity.Fatal,
    summary = ReliabilityRedactor.redact(old.exceptionSummary, 240),
    recovery = "The app restarted. Work already running on Hermes may still be active.",
    reportRecommended = true,
    technicalDetail = ReliabilityRedactor.redact("Thread: ${old.threadName}\n${old.stackTrace}"),
    environment = ReliabilityEnvironment(
        old.versionName, old.versionCode, old.flavor, old.manufacturer, old.model,
        old.androidRelease, old.sdkInt,
    ),
    pendingReview = true,
)

data class ReliabilityClassification(
    val kind: ReliabilityKind,
    val owner: ReliabilityOwner,
    val reportRecommended: Boolean,
    val shouldPersist: Boolean,
)

object ReliabilityClassifier {
    fun classify(throwable: Throwable, context: String? = null): ReliabilityClassification {
        val message = throwable.message.orEmpty().lowercase()
        val owner = ownerForContext(context)
        return when {
            throwable is CancellationException -> ReliabilityClassification(
                ReliabilityKind.ExpectedCancellation, owner, reportRecommended = false, shouldPersist = false,
            )
            throwable is SecurityException && ("denied" in message || "permission" in message) ->
                ReliabilityClassification(
                    ReliabilityKind.UserDenial, ReliabilityOwner.Android,
                    reportRecommended = false, shouldPersist = false,
                )
            "429" in message || "rate limit" in message || "too many requests" in message ->
                ReliabilityClassification(
                    ReliabilityKind.RateLimit, owner, reportRecommended = false, shouldPersist = true,
                )
            "401" in message || "403" in message || "unauthorized" in message || "forbidden" in message ->
                ReliabilityClassification(
                    ReliabilityKind.Authentication, owner, reportRecommended = false, shouldPersist = true,
                )
            throwable is java.net.UnknownHostException ||
                throwable is java.net.ConnectException ||
                throwable is java.net.SocketTimeoutException ||
                "timeout" in message -> ReliabilityClassification(
                ReliabilityKind.Connectivity, owner, reportRecommended = false, shouldPersist = true,
            )
            "503" in message || "service unavailable" in message || "gateway_draining" in message ->
                ReliabilityClassification(
                    ReliabilityKind.ServiceUnavailable, owner,
                    reportRecommended = false, shouldPersist = true,
                )
            else -> ReliabilityClassification(
                ReliabilityKind.RecoverableProductError, owner,
                reportRecommended = true, shouldPersist = true,
            )
        }
    }

    fun ownerForContext(context: String?): ReliabilityOwner = when (context?.lowercase()) {
        "dashboard", "manage", "dashboard_auth" -> ReliabilityOwner.Dashboard
        "gateway", "gateway_chat", "upstream_gateway" -> ReliabilityOwner.UpstreamGateway
        "transcribe", "synthesize", "voice_config", "record", "voice" -> ReliabilityOwner.Voice
        "pair", "save_and_test", "media_fetch", "relay" -> ReliabilityOwner.Relay
        "send_message", "load_sessions", "create_session", "api" -> ReliabilityOwner.Api
        "android", "permission", "ui" -> ReliabilityOwner.Android
        else -> ReliabilityOwner.Unknown
    }
}

/** Local, deterministic redaction. It runs before persistence and again before export. */
object ReliabilityRedactor {
    const val MAX_TECHNICAL_LENGTH = 8_000
    private const val HIDDEN = "[hidden]"

    private val secretAssignment = Regex(
        """(?i)\b(authorization|bearer|cookie|set-cookie|token|api[_-]?key|session[_-]?token|pairing[_-]?code|password|secret|oauth[_-]?code)\s*[:=]\s*((?:Bearer\s+)?[^\s,;]+)""",
    )
    private val sensitiveHeader = Regex("""(?im)^\s*(authorization|cookie|set-cookie)\s*:\s*.+$""")
    private val standaloneBearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
    private val sensitivePayload = Regex(
        """(?i)\b(prompt|message|content|transcript|reasoning|tool[_-]?(args|result)|profile[_-]?name)\s*[:=]\s*([^\r\n]+)""",
    )
    private val url = Regex("""(?i)\b(?:https?|wss?)://[^\s)\]}>,]+""")
    private val ipv4 = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?(?![\w.])""")
    private val uuid = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b""")
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
    private val namedHost = Regex("""(?i)\b(host|hostname)\s*[:=]\s*[^\s,;]+""")
    private val unresolvedHost = Regex("""(?i)(?:resolve|resolved|host)\s+[\"']([^\"']+)[\"']""")
    private val windowsPath = Regex("""(?i)\b[A-Z]:\\(?:[^\s\\]+\\)+[^\s]+""")
    private val unixPrivatePath = Regex("""(?i)(?:/home/|/Users/|/data/user/\d+/|/sdcard/)[^\s]+""")

    fun redact(value: String, maxLength: Int = MAX_TECHNICAL_LENGTH): String {
        var result = value
        result = sensitiveHeader.replace(result) { "${it.groupValues[1]}: $HIDDEN" }
        result = secretAssignment.replace(result) { "${it.groupValues[1]}=$HIDDEN" }
        result = standaloneBearer.replace(result, "Bearer $HIDDEN")
        result = sensitivePayload.replace(result) { "${it.groupValues[1]}=$HIDDEN" }
        result = url.replace(result, "[url hidden]")
        result = ipv4.replace(result, "[host hidden]")
        result = uuid.replace(result, "[id hidden]")
        result = email.replace(result, "[email hidden]")
        result = namedHost.replace(result) { "${it.groupValues[1]}=$HIDDEN" }
        result = unresolvedHost.replace(result, "host \"$HIDDEN\"")
        result = windowsPath.replace(result, "[path hidden]")
        result = unixPrivatePath.replace(result, "[path hidden]")
        return if (result.length > maxLength) {
            result.take(maxLength) + "\n… (truncated)"
        } else {
            result
        }
    }
}

@Serializable
private data class ReliabilityEnvelope(
    val schemaVersion: Int = RELIABILITY_SCHEMA_VERSION,
    val reports: List<ReliabilityReport> = emptyList(),
)

/** Pure file store so retention, bounds, and migration behavior are JVM-testable. */
class ReliabilityStore(
    private val file: File,
    private val maxReports: Int = 20,
    private val retentionDays: Long = 14,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val lock = Any()

    fun readAll(now: Instant = Instant.now()): List<ReliabilityReport> = synchronized(lock) {
        val decoded = decode()
        val retained = prune(decoded, now)
        if (retained != decoded) write(retained)
        retained
    }

    fun append(report: ReliabilityReport, now: Instant = Instant.now()) = synchronized(lock) {
        write(prune(decode() + sanitize(report), now))
    }

    fun markReviewed(reportId: String, now: Instant = Instant.now()) = synchronized(lock) {
        write(
            prune(
                decode().map { if (it.reportId == reportId) it.copy(pendingReview = false) else it },
                now,
            ),
        )
    }

    private fun sanitize(report: ReliabilityReport): ReliabilityReport = report.copy(
        summary = ReliabilityRedactor.redact(report.summary, 240),
        recovery = ReliabilityRedactor.redact(report.recovery, 240),
        technicalDetail = report.technicalDetail?.let(ReliabilityRedactor::redact),
        routeRole = report.routeRole?.let { ReliabilityRedactor.redact(it, 40) },
    )

    private fun prune(reports: List<ReliabilityReport>, now: Instant): List<ReliabilityReport> {
        val cutoff = now.minusSeconds(retentionDays * 24 * 60 * 60)
        return reports
            .distinctBy { it.reportId }
            .filter { report -> runCatching { Instant.parse(report.timeIso) >= cutoff }.getOrDefault(true) }
            .sortedBy { it.timeIso }
            .takeLast(maxReports.coerceAtLeast(1))
    }

    private fun decode(): List<ReliabilityReport> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString<ReliabilityEnvelope>(file.readText()).reports
    }.getOrDefault(emptyList())

    private fun write(reports: List<ReliabilityReport>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(ReliabilityEnvelope(reports = reports)))
        runCatching {
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching {
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }
}

object SupportBundleBuilder {
    const val MAX_REPORTS = 10

    fun build(reports: List<ReliabilityReport>): String {
        val selected = reports.sortedByDescending { it.timeIso }.take(MAX_REPORTS)
        return ReliabilityRedactor.redact(
            buildString {
                appendLine("Hermes-Relay support bundle")
                appendLine("Local-only export · review before sharing")
                appendLine("Reports: ${selected.size}")
                selected.forEachIndexed { index, report ->
                    appendLine()
                    appendLine("===== Report ${index + 1} =====")
                    append(report.toPlainText())
                    appendLine()
                }
            },
            maxLength = 64_000,
        )
    }
}
