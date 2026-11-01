package fit.bandwagon.core.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore by preferencesDataStore(name = "bandwagon_sync")

@Singleton
class SyncPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val tokenKey = stringPreferencesKey("health_changes_token")
    private val scopeKey = stringPreferencesKey("health_changes_scope")

    /**
     * The stored changes token, or null if it was issued for a different set of record types
     * ([scope]). A token only reports changes for the types it was created with, so granting
     * sleep later must start a fresh one.
     */
    suspend fun changesToken(scope: String): String? {
        val preferences = context.syncDataStore.data.first()
        return preferences[tokenKey]?.takeIf { preferences[scopeKey] == scope }
    }

    suspend fun saveChangesToken(token: String?, scope: String) {
        context.syncDataStore.edit { preferences ->
            if (token == null) {
                preferences.remove(tokenKey)
                preferences.remove(scopeKey)
            } else {
                preferences[tokenKey] = token
                preferences[scopeKey] = scope
            }
        }
    }
}
