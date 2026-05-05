package com.aelant.freqshift

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("freqshift")

/** Snapshot of all persisted settings. */
data class PersistedSettings(
    val presetId: String = Frequencies.NONE.id,
    val tuningMode: TuningMode = TuningMode.LINKED,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/**
 * Lightweight persistence layer over DataStore. Writes are fire-and-forget —
 * in-memory state is the runtime source of truth; this just records the
 * last-known values for the next launch.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val PRESET_ID = stringPreferencesKey("preset_id")
        val TUNING_MODE = stringPreferencesKey("tuning_mode")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
    }

    val flow: Flow<PersistedSettings> = context.dataStore.data.map { prefs ->
        PersistedSettings(
            presetId = prefs[Keys.PRESET_ID] ?: Frequencies.NONE.id,
            tuningMode = runCatching {
                TuningMode.valueOf(prefs[Keys.TUNING_MODE] ?: "")
            }.getOrDefault(TuningMode.LINKED),
            shuffle = prefs[Keys.SHUFFLE] ?: false,
            repeatMode = prefs[Keys.REPEAT_MODE] ?: Player.REPEAT_MODE_OFF,
        )
    }

    suspend fun savePreset(id: String) = write { it[Keys.PRESET_ID] = id }
    suspend fun saveTuningMode(mode: TuningMode) = write { it[Keys.TUNING_MODE] = mode.name }
    suspend fun saveShuffle(on: Boolean) = write { it[Keys.SHUFFLE] = on }
    suspend fun saveRepeatMode(mode: Int) = write { it[Keys.REPEAT_MODE] = mode }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
