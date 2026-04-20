"""Unit tests for :class:`plugin.relay.auth.RateLimiter`'s split buckets.

Covers the Commit-2 refactor: pairing-code failures and session-token
failures track independently, blocks are shared (any ban blocks every
bucket), and the backwards-compat ``record_failure`` alias still routes
to the session bucket.
"""

from __future__ import annotations

import unittest

from plugin.relay.auth import (
    RateLimitConfig,
    RateLimiter,
    _PAIRING_BLOCK_SECONDS,
    _PAIRING_MAX_ATTEMPTS,
    _SESSION_BLOCK_SECONDS,
    _SESSION_MAX_ATTEMPTS,
)


class RateLimiterSplitBucketDefaultsTests(unittest.TestCase):
    """Default construction — verify the library defaults match the
    documented wire contract (pairing: 10-in-60s → 2 min, session:
    5-in-60s → 5 min)."""

    def test_default_pairing_config(self) -> None:
        rl = RateLimiter()
        self.assertEqual(rl.pairing_config.max_attempts, 10)
        self.assertEqual(rl.pairing_config.window_seconds, 60)
        self.assertEqual(rl.pairing_config.block_seconds, 120)
        # Sanity — the constants we imported agree.
        self.assertEqual(rl.pairing_config.max_attempts, _PAIRING_MAX_ATTEMPTS)
        self.assertEqual(rl.pairing_config.block_seconds, _PAIRING_BLOCK_SECONDS)

    def test_default_session_config(self) -> None:
        rl = RateLimiter()
        self.assertEqual(rl.session_config.max_attempts, 5)
        self.assertEqual(rl.session_config.window_seconds, 60)
        self.assertEqual(rl.session_config.block_seconds, 300)
        self.assertEqual(rl.session_config.max_attempts, _SESSION_MAX_ATTEMPTS)
        self.assertEqual(rl.session_config.block_seconds, _SESSION_BLOCK_SECONDS)


class RateLimiterBucketIndependenceTests(unittest.TestCase):
    """Each bucket's failure counter ticks independently. Failures
    accrued in one bucket do not advance the other's counter."""

    def test_pairing_bucket_does_not_advance_session(self) -> None:
        rl = RateLimiter(
            pairing_config=RateLimitConfig(
                max_attempts=10, window_seconds=60, block_seconds=120
            ),
            session_config=RateLimitConfig(
                max_attempts=5, window_seconds=60, block_seconds=300
            ),
        )
        # Fire 4 pairing failures — below both thresholds.
        for _ in range(4):
            rl.record_pairing_failure("1.1.1.1")
        self.assertFalse(rl.is_blocked("1.1.1.1"))
        # Session bucket should still be empty for this IP.
        self.assertNotIn("1.1.1.1", rl._session_failures)
        self.assertEqual(len(rl._pairing_failures["1.1.1.1"]), 4)

    def test_session_bucket_does_not_advance_pairing(self) -> None:
        rl = RateLimiter()
        for _ in range(4):
            rl.record_session_failure("2.2.2.2")
        self.assertFalse(rl.is_blocked("2.2.2.2"))
        self.assertNotIn("2.2.2.2", rl._pairing_failures)
        self.assertEqual(len(rl._session_failures["2.2.2.2"]), 4)

    def test_pairing_bucket_needs_more_failures_to_trip(self) -> None:
        """With defaults (pairing=10, session=5), five pairing failures
        should NOT ban the IP (session bucket untouched; pairing bucket
        not yet at max)."""
        rl = RateLimiter()
        for _ in range(5):
            rl.record_pairing_failure("3.3.3.3")
        self.assertFalse(rl.is_blocked("3.3.3.3"))

    def test_pairing_bucket_trips_at_its_max(self) -> None:
        rl = RateLimiter()
        for _ in range(_PAIRING_MAX_ATTEMPTS):
            rl.record_pairing_failure("4.4.4.4")
        self.assertTrue(rl.is_blocked("4.4.4.4"))

    def test_session_bucket_trips_at_its_max(self) -> None:
        rl = RateLimiter()
        for _ in range(_SESSION_MAX_ATTEMPTS):
            rl.record_session_failure("5.5.5.5")
        self.assertTrue(rl.is_blocked("5.5.5.5"))


class RateLimiterSharedBlockStateTests(unittest.TestCase):
    """Either bucket banning an IP blocks every subsequent auth
    attempt, regardless of which bucket the new attempt routes to."""

    def test_session_block_blocks_pairing_attempt(self) -> None:
        rl = RateLimiter()
        for _ in range(_SESSION_MAX_ATTEMPTS):
            rl.record_session_failure("6.6.6.6")
        self.assertTrue(rl.is_blocked("6.6.6.6"))
        # The IP is banned — doesn't matter that the pairing bucket is
        # empty, the caller still sees is_blocked=True.
        self.assertNotIn("6.6.6.6", rl._pairing_failures)
        self.assertTrue(rl.is_blocked("6.6.6.6"))

    def test_pairing_block_blocks_session_attempt(self) -> None:
        rl = RateLimiter()
        for _ in range(_PAIRING_MAX_ATTEMPTS):
            rl.record_pairing_failure("7.7.7.7")
        self.assertTrue(rl.is_blocked("7.7.7.7"))
        self.assertNotIn("7.7.7.7", rl._session_failures)
        self.assertTrue(rl.is_blocked("7.7.7.7"))


class RateLimiterRecordSuccessClearsBothBucketsTests(unittest.TestCase):
    """record_success must drop the IP from both bucket counters — a
    successful auth means no hostile activity from this IP, regardless
    of which path succeeded."""

    def test_success_clears_both_buckets(self) -> None:
        rl = RateLimiter()
        rl.record_pairing_failure("8.8.8.8")
        rl.record_session_failure("8.8.8.8")
        self.assertIn("8.8.8.8", rl._pairing_failures)
        self.assertIn("8.8.8.8", rl._session_failures)

        rl.record_success("8.8.8.8")
        self.assertNotIn("8.8.8.8", rl._pairing_failures)
        self.assertNotIn("8.8.8.8", rl._session_failures)


class RateLimiterClearAllClearsBothBucketsTests(unittest.TestCase):
    """clear_all_blocks (called from loopback pairing endpoints) must
    wipe block state AND both pending-failure dicts."""

    def test_clear_all_drops_everything(self) -> None:
        rl = RateLimiter()
        rl.record_pairing_failure("9.9.9.9")
        rl.record_session_failure("10.10.10.10")
        for _ in range(_SESSION_MAX_ATTEMPTS):
            rl.record_session_failure("11.11.11.11")
        self.assertTrue(rl.is_blocked("11.11.11.11"))

        rl.clear_all_blocks()

        self.assertFalse(rl.is_blocked("11.11.11.11"))
        self.assertEqual(rl._blocked, {})
        self.assertEqual(rl._pairing_failures, {})
        self.assertEqual(rl._session_failures, {})


class RateLimiterBackCompatTests(unittest.TestCase):
    """Legacy positional constructor + record_failure() alias must
    keep working for any imports reaching into the private module."""

    def test_legacy_positional_construct_mirrors_both_buckets(self) -> None:
        rl = RateLimiter(max_attempts=3, window_seconds=60, block_seconds=300)
        # Legacy attributes must reflect the passed value.
        self.assertEqual(rl.max_attempts, 3)
        self.assertEqual(rl.window_seconds, 60)
        self.assertEqual(rl.block_seconds, 300)
        # Both buckets should use the legacy value.
        self.assertEqual(rl.pairing_config.max_attempts, 3)
        self.assertEqual(rl.session_config.max_attempts, 3)

    def test_record_failure_is_alias_for_session_bucket(self) -> None:
        """The back-compat alias must increment the session counter, not
        the pairing counter (session is the strict/default bucket)."""
        rl = RateLimiter()
        rl.record_failure("12.12.12.12")
        self.assertIn("12.12.12.12", rl._session_failures)
        self.assertNotIn("12.12.12.12", rl._pairing_failures)

    def test_legacy_failures_property_merges_both_buckets(self) -> None:
        """``_failures`` is introspected by older tests — verify the
        alias presents a merged view across both split dicts."""
        rl = RateLimiter()
        rl.record_pairing_failure("13.13.13.13")
        rl.record_session_failure("13.13.13.13")
        merged = rl._failures
        self.assertIn("13.13.13.13", merged)
        self.assertEqual(len(merged["13.13.13.13"]), 2)


class RateLimiterBucketExhaustionTests(unittest.TestCase):
    """Once a bucket trips, subsequent failures in that bucket (while
    blocked) don't re-add counts — the block clock is what the caller
    must wait out."""

    def test_blocked_ip_continues_to_show_blocked(self) -> None:
        rl = RateLimiter(
            pairing_config=RateLimitConfig(
                max_attempts=2, window_seconds=60, block_seconds=120
            ),
            session_config=RateLimitConfig(
                max_attempts=2, window_seconds=60, block_seconds=120
            ),
        )
        rl.record_pairing_failure("14.14.14.14")
        rl.record_pairing_failure("14.14.14.14")
        self.assertTrue(rl.is_blocked("14.14.14.14"))
        # Further failures don't unblock or crash.
        rl.record_pairing_failure("14.14.14.14")
        rl.record_session_failure("14.14.14.14")
        self.assertTrue(rl.is_blocked("14.14.14.14"))


if __name__ == "__main__":
    unittest.main()
