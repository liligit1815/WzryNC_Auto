package com.lispace.wzryncauto.device

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

enum class BrightnessLeasePhase {
    APPLYING,
    APPLIED,
    RESTORING,
}

data class BrightnessLease(
    val snapshot: BrightnessSnapshot,
    val phase: BrightnessLeasePhase,
    val updatedAtEpochMs: Long,
)

/**
 * Result of attempting to persist the pre-change display state.
 *
 * An existing lease is an expected recovery condition, not a persistence error. In that case the
 * original lease is returned unchanged and must remain the source of truth until it is restored.
 */
sealed interface SaveBrightnessLeaseResult {
    val lease: BrightnessLease

    data class Saved(
        override val lease: BrightnessLease,
    ) : SaveBrightnessLeaseResult

    data class ExistingLeasePreserved(
        override val lease: BrightnessLease,
    ) : SaveBrightnessLeaseResult
}

/** Raised when a stored recovery record exists but cannot be decoded safely. */
class CorruptBrightnessLeaseException(
    cause: Throwable,
) : IllegalStateException(
    "Brightness recovery lease is corrupt; refusing to overwrite the original record",
    cause,
)

/** Persists the original display state before any brightness write occurs. */
class BrightnessLeaseStore internal constructor(
    private val storage: BrightnessLeaseStorage,
) {
    constructor(context: Context) : this(
        SharedPreferencesBrightnessLeaseStorage(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    /**
     * Saves [snapshot] only when no recovery lease exists.
     *
     * [SaveBrightnessLeaseResult.ExistingLeasePreserved] is returned for the normal crash-recovery
     * case; the caller must use its [SaveBrightnessLeaseResult.lease] as the original display state.
     * A corrupt existing record or a failed durable write throws and is never overwritten.
     */
    fun saveBeforeApply(
        snapshot: BrightnessSnapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SaveBrightnessLeaseResult = synchronized(STORE_LOCK) {
        when (val stored = storage.read()) {
            BrightnessLeaseReadResult.Missing -> {
                val lease = BrightnessLease(
                    snapshot = snapshot,
                    phase = BrightnessLeasePhase.APPLYING,
                    updatedAtEpochMs = nowEpochMs,
                )
                storage.write(lease)
                SaveBrightnessLeaseResult.Saved(lease)
            }

            is BrightnessLeaseReadResult.Found -> {
                SaveBrightnessLeaseResult.ExistingLeasePreserved(stored.lease)
            }

            is BrightnessLeaseReadResult.Corrupt -> {
                throw CorruptBrightnessLeaseException(stored.cause)
            }
        }
    }

    fun markApplied(nowEpochMs: Long = System.currentTimeMillis()) = synchronized(STORE_LOCK) {
        val current = requireStoredLease("mark applied")
        storage.write(
            current.copy(
                phase = BrightnessLeasePhase.APPLIED,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    fun markRestoring(nowEpochMs: Long = System.currentTimeMillis()) = synchronized(STORE_LOCK) {
        val current = requireStoredLease("mark restoring")
        storage.write(
            current.copy(
                phase = BrightnessLeasePhase.RESTORING,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    /** Returns null for a missing or unreadable record; save operations still preserve unreadable data. */
    fun load(): BrightnessLease? = synchronized(STORE_LOCK) {
        when (val stored = storage.read()) {
            BrightnessLeaseReadResult.Missing -> null
            is BrightnessLeaseReadResult.Found -> stored.lease
            is BrightnessLeaseReadResult.Corrupt -> null
        }
    }

    /** True for both a valid lease and a corrupt record that must never be overwritten. */
    fun hasUnresolvedLease(): Boolean = synchronized(STORE_LOCK) {
        storage.read() !is BrightnessLeaseReadResult.Missing
    }

    fun clear() = synchronized(STORE_LOCK) {
        storage.clear()
    }

    private fun requireStoredLease(operation: String): BrightnessLease = when (val stored = storage.read()) {
        BrightnessLeaseReadResult.Missing -> {
            error("Cannot $operation without a brightness recovery lease")
        }

        is BrightnessLeaseReadResult.Found -> stored.lease
        is BrightnessLeaseReadResult.Corrupt -> {
            throw CorruptBrightnessLeaseException(stored.cause)
        }
    }

    private companion object {
        const val PREFERENCES = "brightness_recovery_lease"

        /** Coordinates separate store instances in this single-process application. */
        val STORE_LOCK = Any()
    }
}

internal sealed interface BrightnessLeaseReadResult {
    data object Missing : BrightnessLeaseReadResult

    data class Found(
        val lease: BrightnessLease,
    ) : BrightnessLeaseReadResult

    data class Corrupt(
        val cause: Throwable,
    ) : BrightnessLeaseReadResult
}

internal interface BrightnessLeaseStorage {
    fun read(): BrightnessLeaseReadResult

    fun write(lease: BrightnessLease)

    fun clear()
}

private class SharedPreferencesBrightnessLeaseStorage(
    private val preferences: SharedPreferences,
) : BrightnessLeaseStorage {
    override fun read(): BrightnessLeaseReadResult {
        if (!preferences.contains(KEY_LEASE)) return BrightnessLeaseReadResult.Missing
        val raw = runCatching { preferences.getString(KEY_LEASE, null) }
            .getOrElse { return BrightnessLeaseReadResult.Corrupt(it) }
            ?: return BrightnessLeaseReadResult.Corrupt(
                IllegalStateException("Brightness recovery lease has no string value"),
            )
        return runCatching { decode(raw) }
            .fold(
                onSuccess = BrightnessLeaseReadResult::Found,
                onFailure = BrightnessLeaseReadResult::Corrupt,
            )
    }

    override fun write(lease: BrightnessLease) {
        val encoded = encode(lease)
        check(preferences.edit().putString(KEY_LEASE, encoded).commit()) {
            "Unable to persist brightness recovery lease"
        }
    }

    override fun clear() {
        check(preferences.edit().remove(KEY_LEASE).commit()) {
            "Unable to clear brightness recovery lease"
        }
    }

    private fun encode(lease: BrightnessLease): String {
        val backlights = JSONObject().apply {
            lease.snapshot.backlightValues.forEach { (path, value) -> put(path, value) }
        }
        return JSONObject()
            .put("systemBrightness", lease.snapshot.systemBrightness)
            .put("automaticMode", lease.snapshot.automaticMode)
            .put("backlights", backlights)
            .put("phase", lease.phase.name)
            .put("updatedAtEpochMs", lease.updatedAtEpochMs)
            .toString()
    }

    private fun decode(raw: String): BrightnessLease {
        val root = JSONObject(raw)
        val backlightsJson = root.getJSONObject("backlights")
        val backlights = buildMap {
            backlightsJson.keys().forEach { path ->
                put(path, backlightsJson.getInt(path))
            }
        }
        return BrightnessLease(
            snapshot = BrightnessSnapshot(
                systemBrightness = root.getInt("systemBrightness"),
                automaticMode = root.getInt("automaticMode"),
                backlightValues = backlights,
            ),
            phase = BrightnessLeasePhase.valueOf(root.getString("phase")),
            updatedAtEpochMs = root.getLong("updatedAtEpochMs"),
        )
    }

    private companion object {
        const val KEY_LEASE = "lease_json"
    }
}
