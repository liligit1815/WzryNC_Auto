package com.lispace.wzryncauto.schedule

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId

private val Context.farmStateDataStore by preferencesDataStore(name = "farm_state")

data class StoredFarmState(
    val cycleMinutes: Int,
    val batchStartedAt: LocalDateTime,
    val observedMaturityAt: LocalDateTime?,
    val updatedAt: LocalDateTime,
    val lastConfirmedWateringAt: LocalDateTime? = null,
    val lastAttemptAt: LocalDateTime? = null,
    val cycleEstimated: Boolean = true,
    val confirmedWateringCount: Int = 0,
)

class FarmStateStore(private val context: Context) {
    suspend fun load(): StoredFarmState? {
        val values = context.farmStateDataStore.data.first()
        return runCatching {
            StoredFarmState(
                cycleMinutes = requireNotNull(values[CYCLE_MINUTES]),
                batchStartedAt = readTime(
                    epochMillis = values[BATCH_STARTED_AT_EPOCH],
                    legacy = values[BATCH_STARTED_AT],
                ),
                observedMaturityAt = if (
                    values[OBSERVED_MATURITY_AT_EPOCH] != null ||
                    values[OBSERVED_MATURITY_AT] != null
                ) {
                    readTime(
                        epochMillis = values[OBSERVED_MATURITY_AT_EPOCH],
                        legacy = values[OBSERVED_MATURITY_AT],
                    )
                } else {
                    null
                },
                updatedAt = readTime(
                    epochMillis = values[UPDATED_AT_EPOCH],
                    legacy = values[UPDATED_AT],
                ),
                lastConfirmedWateringAt = values[LAST_CONFIRMED_WATERING_AT]?.let {
                    readTime(it, null)
                },
                lastAttemptAt = values[LAST_ATTEMPT_AT]?.let { readTime(it, null) },
                cycleEstimated = values[CYCLE_ESTIMATED] ?: true,
                confirmedWateringCount = values[CONFIRMED_WATERING_COUNT] ?: 0,
            )
        }.getOrNull()
    }

    suspend fun save(
        cycleMinutes: Int,
        batchStartedAt: LocalDateTime,
        observedMaturityAt: LocalDateTime?,
        updatedAt: LocalDateTime = LocalDateTime.now(),
        lastConfirmedWateringAt: LocalDateTime? = null,
        lastAttemptAt: LocalDateTime? = null,
        cycleEstimated: Boolean = true,
        confirmedWateringCount: Int = 0,
    ) {
        require(cycleMinutes in setOf(5, 60, 480, 960, 1920))
        context.farmStateDataStore.edit { values ->
            values[CYCLE_MINUTES] = cycleMinutes
            values[BATCH_STARTED_AT_EPOCH] = batchStartedAt.toEpochMillis()
            values.remove(BATCH_STARTED_AT)
            if (observedMaturityAt == null) {
                values.remove(OBSERVED_MATURITY_AT_EPOCH)
                values.remove(OBSERVED_MATURITY_AT)
            } else {
                values[OBSERVED_MATURITY_AT_EPOCH] = observedMaturityAt.toEpochMillis()
                values.remove(OBSERVED_MATURITY_AT)
            }
            values[UPDATED_AT_EPOCH] = updatedAt.toEpochMillis()
            values.remove(UPDATED_AT)
            if (lastConfirmedWateringAt != null) {
                values[LAST_CONFIRMED_WATERING_AT] = lastConfirmedWateringAt.toEpochMillis()
            } else {
                values.remove(LAST_CONFIRMED_WATERING_AT)
            }
            if (lastAttemptAt != null) {
                values[LAST_ATTEMPT_AT] = lastAttemptAt.toEpochMillis()
            } else {
                values.remove(LAST_ATTEMPT_AT)
            }
            values[CYCLE_ESTIMATED] = cycleEstimated
            values[CONFIRMED_WATERING_COUNT] = confirmedWateringCount
        }
    }

    suspend fun clear() {
        context.farmStateDataStore.edit { it.clear() }
    }

    private fun readTime(epochMillis: Long?, legacy: String?): LocalDateTime =
        epochMillis?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
        } ?: LocalDateTime.parse(requireNotNull(legacy))

    private fun LocalDateTime.toEpochMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        val CYCLE_MINUTES = intPreferencesKey("cycle_minutes")
        val BATCH_STARTED_AT = stringPreferencesKey("batch_started_at")
        val OBSERVED_MATURITY_AT = stringPreferencesKey("observed_maturity_at")
        val UPDATED_AT = stringPreferencesKey("updated_at")
        val BATCH_STARTED_AT_EPOCH = longPreferencesKey("batch_started_at_epoch_ms")
        val OBSERVED_MATURITY_AT_EPOCH = longPreferencesKey("observed_maturity_at_epoch_ms")
        val UPDATED_AT_EPOCH = longPreferencesKey("updated_at_epoch_ms")
        val LAST_CONFIRMED_WATERING_AT = longPreferencesKey("last_confirmed_watering_at")
        val LAST_ATTEMPT_AT = longPreferencesKey("last_attempt_at")
        val CYCLE_ESTIMATED = booleanPreferencesKey("cycle_estimated")
        val CONFIRMED_WATERING_COUNT = intPreferencesKey("confirmed_watering_count")
    }
}
