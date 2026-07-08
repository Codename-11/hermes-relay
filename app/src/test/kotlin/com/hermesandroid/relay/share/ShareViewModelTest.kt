package com.hermesandroid.relay.share

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    @Test
    fun init_createsDurableCaptureBeforeAction() = runTest {
        val sender = FakeShareSender(createResult = Result.success(CreatedShareCapture("cap_1")))

        val vm = ShareViewModel(samplePayload(), sender, this)
        advanceUntilIdle()

        assertEquals(1, sender.created.size)
        assertEquals("cap_1", vm.state.value.captureId)
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun submitSelectedAction_postsSelectedCanonicalAction() = runTest {
        val sender = FakeShareSender(
            createResult = Result.success(CreatedShareCapture("cap_1")),
            actResult = Result.success(ShareActionResult("handled")),
        )
        val vm = ShareViewModel(samplePayload(), sender, this)
        advanceUntilIdle()

        vm.selectAction(ShareAction.Research)
        vm.submitSelectedAction()
        advanceUntilIdle()

        assertEquals(listOf("cap_1" to ShareAction.Research), sender.acted)
        assertEquals("Capture handled.", vm.state.value.successMessage)
    }

    @Test
    fun alreadyHandled_isTerminalSuccess() = runTest {
        val sender = FakeShareSender(
            createResult = Result.success(CreatedShareCapture("cap_1")),
            actResult = Result.success(ShareActionResult("already-handled")),
        )
        val vm = ShareViewModel(samplePayload(), sender, this)
        advanceUntilIdle()

        vm.submitSelectedAction()
        advanceUntilIdle()

        assertFalse(vm.state.value.canSubmit)
        assertEquals("Capture already-handled.", vm.state.value.successMessage)
    }

    @Test
    fun createFailure_keepsActionDisabled() = runTest {
        val sender = FakeShareSender(createResult = Result.failure(IllegalStateException("boom")))

        val vm = ShareViewModel(samplePayload(), sender, this)
        advanceUntilIdle()

        assertFalse(vm.state.value.canSubmit)
        assertEquals("boom", vm.state.value.errorMessage)
    }

    private fun TestScope.samplePayload() = SharePayload(
        text = "Shared text",
        mimeType = "text/plain",
        sourceApp = "Chrome",
        timestamp = testScheduler.currentTime.toString(),
    )

    private class FakeShareSender(
        private val createResult: Result<CreatedShareCapture>,
        private val actResult: Result<ShareActionResult> = Result.success(ShareActionResult("handled")),
    ) : ShareCaptureContract {
        val created = mutableListOf<SharePayload>()
        val acted = mutableListOf<Pair<String, ShareAction>>()

        override suspend fun createCapture(payload: SharePayload): Result<CreatedShareCapture> {
            created += payload
            return createResult
        }

        override suspend fun actOnCapture(captureId: String, action: ShareAction): Result<ShareActionResult> {
            acted += captureId to action
            return actResult
        }
    }
}
