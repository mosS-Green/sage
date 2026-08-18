package com.mossgreen.sage.manager

import android.content.Context
import android.content.SharedPreferences
import com.mossgreen.sage.model.PrefKeys

class LastFmManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getUsername(): String = prefs.getString(PrefKeys.LASTFM_USERNAME, "")?.trim() ?: ""

    fun setUsername(username: String) {
        prefs.edit().putString(PrefKeys.LASTFM_USERNAME, username.trim()).apply()
    }

    fun getApiKey(): String = prefs.getString(PrefKeys.LASTFM_API_KEY, "")?.trim() ?: ""

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(PrefKeys.LASTFM_API_KEY, apiKey.trim()).apply()
    }

    fun getShownName(): String {
        val custom = prefs.getString(PrefKeys.LASTFM_SHOWN_NAME, "")?.trim() ?: ""
        if (custom.isNotEmpty()) return custom
        val username = getUsername()
        return if (username.isNotEmpty()) username else "I"
    }

    fun setShownName(name: String) {
        prefs.edit().putString(PrefKeys.LASTFM_SHOWN_NAME, name.trim()).apply()
    }

    fun getVerb(): String {
        val verb = prefs.getString(PrefKeys.LASTFM_VERB, "")?.trim() ?: ""
        return if (verb.isNotEmpty()) verb else "listening"
    }

    fun setVerb(verb: String) {
        prefs.edit().putString(PrefKeys.LASTFM_VERB, verb.trim()).apply()
    }

    fun isConfigured(): Boolean {
        return getUsername().isNotEmpty() && getApiKey().isNotEmpty()
    }
}
