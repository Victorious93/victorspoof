package com.victorious.spoof.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.victorious.spoof.audio.VoicePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Voice-changer preset persisted in Jetpack DataStore. Unknown/legacy values fall back to NORMAL. */
class VoicePrefs(private val store: DataStore<Preferences>) {
    val preset: Flow<VoicePreset> = store.data.map { VoicePreset.fromId(it[KEY]) }
    suspend fun setPreset(p: VoicePreset) { store.edit { it[KEY] = p.id } }
    companion object { val KEY = stringPreferencesKey("ui.voicePreset") }
}
