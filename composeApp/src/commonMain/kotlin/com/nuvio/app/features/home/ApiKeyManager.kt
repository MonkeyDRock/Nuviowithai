package com.nuvio.app.features.home

import android.content.Context
import android.content.SharedPreferences

class SharedPrefsApiKeyManager(context: Context) : ApiKeyManager {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "nuvio_ai_prefs", 
        Context.MODE_PRIVATE
    )
    
    override fun getKey(): String? {
        return prefs.getString("gemini_api_key", null)
    }

    override fun saveKey(key: String) {
        prefs.edit().putString("gemini_api_key", key).apply()
    }
}
