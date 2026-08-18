package com.mossgreen.sage.manager

import android.content.Context
import android.content.SharedPreferences
import com.mossgreen.sage.model.PrefKeys
import org.json.JSONArray

class MonitoredChatsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Synchronized
    fun isEnabled(): Boolean {
        return prefs.getBoolean(PrefKeys.AUTO_TRANSCRIBE_ENABLED, false)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PrefKeys.AUTO_TRANSCRIBE_ENABLED, enabled).apply()
    }

    @Synchronized
    fun getMonitoredChats(): List<String> {
        val raw = prefs.getString(PrefKeys.MONITORED_CHATS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                arr.optString(idx).takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addChat(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return false
        val current = getMonitoredChats().toMutableList()
        if (current.contains(trimmed)) return false
        current.add(trimmed)
        saveList(current)
        return true
    }

    @Synchronized
    fun removeChat(name: String): Boolean {
        val trimmed = name.trim()
        val current = getMonitoredChats().toMutableList()
        val removed = current.remove(trimmed)
        if (removed) {
            saveList(current)
        }
        return removed
    }

    @Synchronized
    fun isMonitored(chatName: String?): Boolean {
        val monitored = getMonitoredChats()
        // If no specific chats are configured, all chats are eligible
        if (monitored.isEmpty()) return true
        if (chatName.isNullOrBlank()) return false
        val cleanChatName = chatName.trim()
        return monitored.any { it.equals(cleanChatName, ignoreCase = false) || it.equals(cleanChatName, ignoreCase = true) }
    }

    private fun saveList(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(PrefKeys.MONITORED_CHATS, arr.toString()).apply()
    }
}
