package com.hermesandroid.relay.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.coJustRun
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class DataManagerIoTest {
    @Test
    fun writeBackupFailsWhenProviderReturnsNoOutputStream() = runTest {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(uri) } returns null

        val success = DataManager(context).writeBackupToUri(uri, "{}")

        assertFalse(success)
    }

    @Test
    fun restoreTreatsEmptyConnectionsAsReplacementSnapshot() = runTest {
        val context = mockk<Context>()
        val store = mockk<ConnectionStore>()
        every { context.filesDir } returns File("build/tmp/data-manager-test/files")
        coJustRun {
            store.replaceConnections(
                connections = emptyList(),
                activeConnectionId = null,
                startupConnectionId = null,
            )
        }

        DataManager(context, store).restoreConnectionBackup(DataManager.AppBackup())

        coVerify(exactly = 1) {
            store.replaceConnections(
                connections = emptyList(),
                activeConnectionId = null,
                startupConnectionId = null,
            )
        }
    }
}
