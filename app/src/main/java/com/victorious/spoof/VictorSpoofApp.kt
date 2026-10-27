package com.victorious.spoof

import android.app.Application
import com.victorious.spoof.theme.FleetThemeClient
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VictorSpoofApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FleetThemeClient.start(this)
    }
}
