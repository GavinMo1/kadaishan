package dev.sxc.kadaishan.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReviewHistoryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `write failure is returned to caller instead of acknowledged`() = runBlocking {
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                throw java.io.IOException("disk full")
            }
        }
        val job = SupervisorJob()
        try {
            val repo = ReviewHistoryRepository(store, CoroutineScope(job + Dispatchers.IO))
            try {
                repo.setKept(setOf(7L), true)
                org.junit.Assert.fail("failed writes must not be acknowledged")
            } catch (_: java.io.IOException) { }
        } finally { job.cancelAndJoin() }
    }

    @Test fun `setKept returns only after disk update completes`() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val prefs = androidx.datastore.preferences.core.emptyPreferences()
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(prefs)
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                entered.complete(Unit)
                release.await()
                return transform(prefs)
            }
        }
        val job = SupervisorJob()
        try {
            val repo = ReviewHistoryRepository(store, CoroutineScope(job + Dispatchers.IO))
            val call = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { repo.setKept(setOf(7L), true) }
            entered.await()
            val returnedBeforeCommit = call.isCompleted
            release.complete(Unit)
            call.await()
            org.junit.Assert.assertFalse("UI must not advance before commit", returnedBeforeCommit)
        } finally { job.cancelAndJoin() }
    }

    @Test fun `keep decisions survive datastore recreation and undo is ordered`() = runBlocking {
        val file = File(temporary.root, "history.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        try {
            val first = ReviewHistoryRepository(
                PreferenceDataStoreFactory.create(scope = firstScope) { file }, firstScope,
            )
            first.setKept(setOf(1L, 2L), true)
            first.setKept(setOf(2L), false)
            first.setKept(setOf(3L), true)
            // Destroy immediately after acknowledged writes, without a read that could flush a queue.
        } finally {
            firstJob.cancelAndJoin()
        }

        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        try {
            val second = ReviewHistoryRepository(
                PreferenceDataStoreFactory.create(scope = secondScope) { file }, secondScope,
            )
            assertEquals(setOf(1L, 3L), second.keptIds())
            second.clear()
            assertEquals(emptySet<Long>(), second.keptIds())
        } finally {
            secondJob.cancelAndJoin()
        }
    }
}
