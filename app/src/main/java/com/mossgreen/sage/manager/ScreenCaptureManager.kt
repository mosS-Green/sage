package com.mossgreen.sage.manager

import android.content.Context
import android.content.SharedPreferences
import com.mossgreen.sage.model.ScreenCaptureLog
import org.json.JSONArray
import org.json.JSONObject

class ScreenCaptureManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("screen_captures", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CAPTURES = "captures_list"
        private const val MAX_CAPTURES = 50
    }

    @Synchronized
    fun saveCapture(log: ScreenCaptureLog) {
        val current = getCaptures().toMutableList()
        // Insert newest at the beginning
        current.add(0, log)
        // Prune older items beyond maximum limit
        while (current.size > MAX_CAPTURES) {
            current.removeAt(current.size - 1)
        }

        val jsonArray = JSONArray()
        for (item in current) {
            jsonArray.put(item.toJson())
        }
        prefs.edit().putString(KEY_CAPTURES, jsonArray.toString()).apply()
    }

    @Synchronized
    fun getCaptures(): List<ScreenCaptureLog> {
        val raw = prefs.getString(KEY_CAPTURES, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(raw)
            val list = ArrayList<ScreenCaptureLog>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                list.add(ScreenCaptureLog.fromJson(obj))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun deleteCapture(id: String) {
        val current = getCaptures().filter { it.id != id }
        val jsonArray = JSONArray()
        for (item in current) {
            jsonArray.put(item.toJson())
        }
        prefs.edit().putString(KEY_CAPTURES, jsonArray.toString()).apply()
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_CAPTURES).apply()
    }

    fun getCaptureCount(): Int {
        return getCaptures().size
    }
}
