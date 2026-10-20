package com.victorious.spoof.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.victorious.spoof.audio.VoicePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoicePrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultIsNormal() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val p = VoicePrefs(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("a.preferences_pb").also { it.delete() } })
        assertEquals(VoicePreset.NORMAL, p.preset.first()); scope.cancel()
    }

    @Test fun presetSurvivesNewDataStoreInstance() = runBlocking {
        val file = java.io.File(tmp.root, "voice.preferences_pb")
        val s1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        VoicePrefs(PreferenceDataStoreFactory.create(scope = s1) { file }).setPreset(VoicePreset.ROBOT)
        s1.cancel(); Thread.sleep(100) // release the single-process lock on the file
        val s2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val again = VoicePrefs(PreferenceDataStoreFactory.create(scope = s2) { file })
        assertEquals(VoicePreset.ROBOT, again.preset.first())
        again.setPreset(VoicePreset.ECHO); assertEquals(VoicePreset.ECHO, again.preset.first()); s2.cancel()
    }
}
