package com.lili.wzryfarm.schedule

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

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
                batchStartedAt = LocalDateTime.parse(requireNotNull(values[BATCH_STARTED_AT])),
                observedMaturityAt = values[OBSERVED_MATURITY_AT]?.let(LocalDateTime::parse),
                updatedAt = LocalDateTime.parse(requireNotNull(values[UPDATED_AT])),
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
            values[BATCH_STARTED_AT] = batchStartedAt.toString()
            if (observedMaturityAt == null) {
                values.remove(OBSERVED_MATURITY_AT)
            } else {
                values[OBSERVED_MATURITY_AT] = observedMaturityAt.toString()
            }
            values[UPDATED_AT] = updatedAt.toString()
        }
    }

    suspend fun clear() {
        context.farmStateDataStore.edit { it.clear() }
    }

    private companion object {
        val CYCLE_MINUTES = intPreferencesKey("cycle_minutes")
        val BATCH_STARTED_AT = stringPreferencesKey("batch_started_at")
        val OBSERVED_MATURITY_AT = stringPreferencesKey("observed_maturity_at")
        val UPDATED_AT = stringPreferencesKey("updated_at")
    }
}
