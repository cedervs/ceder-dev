package com.cedervs.worlddiscovery.core.location

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.backgroundTrackingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "background_tracking_consent",
)

/** [BackgroundTrackingConsent] backed by Jetpack DataStore — a single boolean, off by default. */
class DataStoreBackgroundTrackingConsent(context: Context) : BackgroundTrackingConsent {

    private val appContext = context.applicationContext
    private val enabledKey = booleanPreferencesKey("background_tracking_enabled")

    override val isEnabled: Flow<Boolean> =
        appContext.backgroundTrackingDataStore.data.map { preferences -> preferences[enabledKey] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        appContext.backgroundTrackingDataStore.edit { preferences -> preferences[enabledKey] = enabled }
    }
}
