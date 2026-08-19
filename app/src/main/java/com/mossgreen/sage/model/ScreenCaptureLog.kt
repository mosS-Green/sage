package com.mossgreen.sage.model

import org.json.JSONObject

data class ScreenCaptureLog(
    val id: String,
    val timestamp: Long,
    val formattedTime: String,
    val primaryPackage: String,
    val windowCount: Int,
    val nodeCount: Int,
    val textSummary: String,
    val interactiveSummary: String,
    val treeDump: String,
    val fullDump: String,
    val jsonDump: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("formattedTime", formattedTime)
            put("primaryPackage", primaryPackage)
            put("windowCount", windowCount)
            put("nodeCount", nodeCount)
            put("textSummary", textSummary)
            put("interactiveSummary", interactiveSummary)
            put("treeDump", treeDump)
            put("fullDump", fullDump)
            put("jsonDump", jsonDump)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ScreenCaptureLog {
            return ScreenCaptureLog(
                id = json.optString("id", System.currentTimeMillis().toString()),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                formattedTime = json.optString("formattedTime", ""),
                primaryPackage = json.optString("primaryPackage", "Unknown"),
                windowCount = json.optInt("windowCount", 1),
                nodeCount = json.optInt("nodeCount", 0),
                textSummary = json.optString("textSummary", ""),
                interactiveSummary = json.optString("interactiveSummary", ""),
                treeDump = json.optString("treeDump", ""),
                fullDump = json.optString("fullDump", ""),
                jsonDump = json.optString("jsonDump", "{}")
            )
        }
    }
}
