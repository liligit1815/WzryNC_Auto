package com.lispace.wzryncauto.schedule

import android.content.Context
import androidx.datastore.preferences.core.edit
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
            )
        }.getOrNull()
    }

    suspend fun save(
        cycleMinutes: Int,
        batchStartedAt: LocalDateTime,
        observedMaturityAt: LocalDateTime?,
        updatedAt: LocalDateTime = LocalDateTime.now(),
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
    }
}
