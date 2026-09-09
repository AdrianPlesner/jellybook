package dk.azp.jellybook.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal val Context.jellybookDataStore: DataStore<Preferences> by preferencesDataStore(name = "jellybook")

internal val storeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** A preference holding a JSON encoded list; the building block for the small local tables the app keeps. */
internal class JsonListPreference<T>(
    private val dataStore: DataStore<Preferences>,
    keyName: String,
    elementSerializer: KSerializer<T>,
) {
    private val key = stringPreferencesKey(keyName)
    private val serializer = ListSerializer(elementSerializer)

    val flow: Flow<List<T>> = dataStore.data.map { decode(it[key]) }

    suspend fun read(): List<T> = decode(dataStore.data.first()[key])

    suspend fun update(transform: (List<T>) -> List<T>) {
        dataStore.edit { prefs -> prefs[key] = storeJson.encodeToString(serializer, transform(decode(prefs[key]))) }
    }

    suspend fun clear() {
        dataStore.edit { prefs -> prefs.remove(key) }
    }

    private fun decode(raw: String?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { storeJson.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
