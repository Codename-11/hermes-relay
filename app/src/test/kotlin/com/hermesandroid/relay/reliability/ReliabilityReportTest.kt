package com.hermesandroid.relay.reliability

import java.io.File
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.CancellationException
import java.net.URLDecoder
import com.hermesandroid.relay.util.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityReportTest {
    private val environment = ReliabilityEnvironment(
        versionName = "1.6.1",
        versionCode = 38,
        flavor = "sideload",
        manufacturer = "Example",
        model = "Phone",
        androidRelease = "16",
        sdkInt = 36,
    )

    @Test
    fun redactorRemovesSecretsHostsIdentifiersPathsAndContent() {
        val raw = """
            authorization: Bearer-secret
            token=abc123
            https://private.example.test:8767/path?q=secret
            host 192.168.1.4:8642
            session 123e4567-e89b-12d3-a456-426614174000
            C:\Users\person\private\file.txt
            prompt=private words from a conversation
        """.trimIndent()

        val redacted = ReliabilityRedactor.redact(raw)

        listOf(
            "Bearer-secret", "abc123", "private.example.test", "192.168.1.4",
            "123e4567-e89b-12d3-a456-426614174000", "person", "private words",
        ).forEach { assertFalse("leaked $it", redacted.contains(it)) }
        assertTrue(redacted.contains("[url hidden]"))
        assertTrue(redacted.contains("prompt=[hidden]"))
    }

    @Test
    fun classifierSeparatesCancellationConnectivityAuthRateLimitAndProductErrors() {
        assertEquals(
            ReliabilityKind.ExpectedCancellation,
            ReliabilityClassifier.classify(CancellationException(), "gateway").kind,
        )
        assertFalse(ReliabilityClassifier.classify(CancellationException(), "gateway").shouldPersist)
        assertEquals(
            ReliabilityKind.Connectivity,
            ReliabilityClassifier.classify(SocketTimeoutException("slow"), "gateway").kind,
        )
        assertEquals(
            ReliabilityOwner.UpstreamGateway,
            ReliabilityClassifier.classify(SocketTimeoutException("slow"), "gateway").owner,
        )
        assertEquals(
            ReliabilityKind.Authentication,
            ReliabilityClassifier.classify(IllegalStateException("HTTP 401"), "dashboard").kind,
        )
        assertEquals(
            ReliabilityKind.RateLimit,
            ReliabilityClassifier.classify(IllegalStateException("HTTP 429"), "api").kind,
        )
        assertTrue(
            ReliabilityClassifier.classify(IllegalArgumentException("duplicate key"), "ui")
                .reportRecommended,
        )
    }

    @Test
    fun storeEnforcesAgeCountRedactionAndReviewedState() {
        val dir = kotlin.io.path.createTempDirectory("reliability-store").toFile()
        val store = ReliabilityStore(File(dir, "reports.json"), maxReports = 3, retentionDays = 14)
        val now = Instant.parse("2026-08-04T12:00:00Z")

        store.append(report("old", "2026-07-01T00:00:00Z"), now)
        store.append(report("one", "2026-08-01T00:00:00Z"), now)
        store.append(report("two", "2026-08-02T00:00:00Z"), now)
        store.append(report("three", "2026-08-03T00:00:00Z"), now)
        store.append(
            report("four", "2026-08-04T00:00:00Z").copy(summary = "token=do-not-store"),
            now,
        )

        val stored = store.readAll(now)
        assertEquals(listOf("two", "three", "four"), stored.map { it.reportId })
        assertFalse(stored.last().summary.contains("do-not-store"))
        assertTrue(stored.last().pendingReview)

        store.markReviewed("four", now)
        assertFalse(store.readAll(now).last().pendingReview)
    }

    @Test
    fun legacyMigrationIsVersionedPendingAndRedacted() {
        val migrated = migrateLegacyCrash(
            LegacyCrashSnapshot(
                timeIso = "2026-08-03T15:12:26Z",
                versionName = "1.6.0",
                versionCode = 37,
                flavor = "googlePlay",
                manufacturer = "Example",
                model = "Phone",
                androidRelease = "17",
                sdkInt = 37,
                threadName = "main",
                exceptionSummary = "Crash token=secret-value",
                stackTrace = "at Example https://private.example.test/path",
            ),
            reportId = "rpt-test",
            appSessionId = "legacy-test",
        )

        assertEquals(RELIABILITY_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(ReliabilityKind.FatalCrash, migrated.kind)
        assertTrue(migrated.pendingReview)
        assertFalse(migrated.toPlainText().contains("secret-value"))
        assertFalse(migrated.toPlainText().contains("private.example.test"))
    }

    @Test
    fun supportBundleIsBoundedAndUsesExactRedactedReports() {
        val reports = (1..12).map { index ->
            report("r$index", "2026-08-${index.toString().padStart(2, '0')}T00:00:00Z")
                .copy(technicalDetail = "token=secret-$index")
        }
        val bundle = SupportBundleBuilder.build(reports)

        assertFalse(bundle.contains("secret-"))
        assertFalse(bundle.contains("Report:  r1\n"))
        assertTrue(bundle.contains("Report:  r12"))
        assertEquals(10, Regex("===== Report ").findAll(bundle).count())
    }

    @Test
    fun crashIssuePrefillTargetsAndroidAndContainsOnlyRedactedDetail() {
        val report = report("rpt-prefill", "2026-08-04T00:00:00Z").copy(
            summary = "Crash token=private-value",
            technicalDetail = "at Example https://private.example.test/path",
        )

        val url = CrashReporter.buildGithubIssueUrl(report)
        val decoded = URLDecoder.decode(url, "UTF-8")

        assertTrue(decoded.contains("labels=bug,area:android"))
        assertTrue(decoded.contains("### Affected area\nAndroid app"))
        assertFalse(decoded.contains("private-value"))
        assertFalse(decoded.contains("private.example.test"))
    }

    private fun report(id: String, time: String): ReliabilityReport = ReliabilityReport(
        reportId = id,
        appSessionId = "app-test",
        timeIso = time,
        kind = ReliabilityKind.FatalCrash,
        owner = ReliabilityOwner.Android,
        severity = ReliabilitySeverity.Fatal,
        summary = "Unexpected problem",
        recovery = "The app restarted",
        reportRecommended = true,
        technicalDetail = "java.lang.IllegalStateException",
        environment = environment,
        pendingReview = true,
    )
}
