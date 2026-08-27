package com.lispace.wzryncauto.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrightnessLeaseStoreTest {
    @Test
    fun savesApplyingLeaseWhenNoRecoveryRecordExists() {
        val storage = FakeBrightnessLeaseStorage()
        val store = BrightnessLeaseStore(storage)
        val snapshot = snapshot(systemBrightness = 140)

        val result = store.saveBeforeApply(snapshot, nowEpochMs = 1234L)

        assertTrue(result is SaveBrightnessLeaseResult.Saved)
        assertEquals(snapshot, result.lease.snapshot)
        assertEquals(BrightnessLeasePhase.APPLYING, result.lease.phase)
        assertEquals(1234L, result.lease.updatedAtEpochMs)
        assertEquals(listOf(result.lease), storage.writes)
    }

    @Test
    fun preservesEveryUnrestoredLeasePhaseAndIgnoresNewSnapshot() {
        BrightnessLeasePhase.entries.forEach { phase ->
            val existing = lease(
                brightness = 26,
                phase = phase,
                updatedAtEpochMs = 100L,
            )
            val storage = FakeBrightnessLeaseStorage(
                initial = BrightnessLeaseReadResult.Found(existing),
            )
            val result = BrightnessLeaseStore(storage).saveBeforeApply(
                snapshot = snapshot(systemBrightness = 1),
                nowEpochMs = 999L,
            )

            assertTrue(result is SaveBrightnessLeaseResult.ExistingLeasePreserved)
            assertSame(existing, result.lease)
            assertTrue("Existing $phase lease must not be written", storage.writes.isEmpty())
            assertEquals(BrightnessLeaseReadResult.Found(existing), storage.current)
        }
    }

    @Test
    fun corruptRecordThrowsAndIsNeverOverwritten() {
        val corruption = IllegalArgumentException("bad lease JSON")
        val storage = FakeBrightnessLeaseStorage(
            initial = BrightnessLeaseReadResult.Corrupt(corruption),
        )

        val thrown = expectThrows<CorruptBrightnessLeaseException> {
            BrightnessLeaseStore(storage).saveBeforeApply(snapshot(80), nowEpochMs = 2L)
        }

        assertSame(corruption, thrown.cause)
        assertTrue(storage.writes.isEmpty())
        assertEquals(BrightnessLeaseReadResult.Corrupt(corruption), storage.current)
    }

    @Test
    fun persistenceFailureIsThrownInsteadOfReportingSaved() {
        val writeFailure = IllegalStateException("disk full")
        val storage = FakeBrightnessLeaseStorage(writeFailure = writeFailure)

        val thrown = expectThrows<IllegalStateException> {
            BrightnessLeaseStore(storage).saveBeforeApply(snapshot(90), nowEpochMs = 3L)
        }

        assertSame(writeFailure, thrown)
        assertEquals(BrightnessLeaseReadResult.Missing, storage.current)
    }

    @Test
    fun phaseTransitionsKeepOriginalSnapshot() {
        val original = lease(
            brightness = 26,
            phase = BrightnessLeasePhase.APPLYING,
            updatedAtEpochMs = 1L,
        )
        val storage = FakeBrightnessLeaseStorage(
            initial = BrightnessLeaseReadResult.Found(original),
        )
        val store = BrightnessLeaseStore(storage)

        store.markApplied(nowEpochMs = 2L)
        store.markRestoring(nowEpochMs = 3L)

        val restored = store.load()
        assertEquals(original.snapshot, restored?.snapshot)
        assertEquals(BrightnessLeasePhase.RESTORING, restored?.phase)
        assertEquals(3L, restored?.updatedAtEpochMs)
    }

    private fun snapshot(systemBrightness: Int) = BrightnessSnapshot(
        systemBrightness = systemBrightness,
        automaticMode = 0,
        backlightValues = mapOf("/sys/class/backlight/panel0/brightness" to systemBrightness),
    )

    private fun lease(
        brightness: Int,
        phase: BrightnessLeasePhase,
        updatedAtEpochMs: Long,
    ) = BrightnessLease(
        snapshot = snapshot(brightness),
        phase = phase,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw throwable
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    }

    private class FakeBrightnessLeaseStorage(
        initial: BrightnessLeaseReadResult = BrightnessLeaseReadResult.Missing,
        private val writeFailure: Throwable? = null,
    ) : BrightnessLeaseStorage {
        var current: BrightnessLeaseReadResult = initial
            private set
        val writes = mutableListOf<BrightnessLease>()

        override fun read(): BrightnessLeaseReadResult = current

        override fun write(lease: BrightnessLease) {
            writeFailure?.let { throw it }
            writes += lease
            current = BrightnessLeaseReadResult.Found(lease)
        }

        override fun clear() {
            current = BrightnessLeaseReadResult.Missing
        }
    }
}
