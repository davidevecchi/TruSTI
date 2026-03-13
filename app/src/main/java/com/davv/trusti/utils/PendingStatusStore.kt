package com.davv.trusti.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingStatusUpdate(
    val targetPublicKey: String,
    val hasPositive: Boolean,
    val timestamp: Long
)

object PendingStatusStore {
    private const val PREFS = "trusti_pending_status"
    private const val KEY = "updates"
    private const val TTL_MS = 7 * 24 * 60 * 60 * 1000L

    /** Store the latest status for a contact, replacing any previous entry. */
    fun addUpdate(context: Context, targetPublicKey: String, hasPositive: Boolean, timestamp: Long = System.currentTimeMillis()) {
        synchronized(this) {
            val entry = PendingStatusUpdate(targetPublicKey, hasPositive, timestamp)
            persist(context, load(context).filter { it.targetPublicKey != targetPublicKey } + entry)
        }
    }

    /** Atomically fetch and remove the single pending update for a contact, or null if none. */
    fun consumeUpdate(context: Context, publicKey: String): PendingStatusUpdate? {
        synchronized(this) {
            val all = load(context)
            val match = all.firstOrNull { it.targetPublicKey == publicKey } ?: return null
            persist(context, all.filter { it.targetPublicKey != publicKey })
            return match
        }
    }

    fun load(context: Context): List<PendingStatusUpdate> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val now = System.currentTimeMillis()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val ts = obj.getLong("timestamp")
                if (now - ts > TTL_MS) return@mapNotNull null
                PendingStatusUpdate(
                    targetPublicKey = obj.getString("targetPublicKey"),
                    hasPositive = obj.getBoolean("hasPositive"),
                    timestamp = ts
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun persist(context: Context, updates: List<PendingStatusUpdate>) {
        val json = JSONArray(updates.map { u ->
            JSONObject()
                .put("targetPublicKey", u.targetPublicKey)
                .put("hasPositive", u.hasPositive)
                .put("timestamp", u.timestamp)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }
}
