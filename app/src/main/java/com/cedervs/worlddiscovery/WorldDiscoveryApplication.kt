package com.cedervs.worlddiscovery

import android.app.Application
import com.cedervs.worlddiscovery.di.AppContainer

class WorldDiscoveryApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
