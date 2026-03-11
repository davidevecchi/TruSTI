package com.davv.trusti.utils

import android.content.Context
import com.davv.trusti.model.Message
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object MessageStore {

    private const val PREFS = "trusti_messages"
    private const val MAX_MESSAGES = 200

    fun save(context: Context, message: Message) {
        val key = keyFor(message.contactPublicKey)
        val list = load(context, message.contactPublicKey).toMutableList()
        list.add(message)
        val arr = JSONArray(list.takeLast(MAX_MESSAGES).map { m ->
            JSONObject().apply {
                put("id", m.id)
                put("contactPublicKey", m.contactPublicKey)
                put("content", m.content)
                put("timestamp", m.timestamp)
                put("isOutbound", m.isOutbound)
            }
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, arr.toString()).apply()
    }

    fun load(context: Context, contactPublicKey: String): List<Message> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(keyFor(contactPublicKey), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { obj ->
                    Message(
                        id = obj.getString("id"),
                        contactPublicKey = obj.getString("contactPublicKey"),
                        content = obj.getString("content"),
                        timestamp = obj.getLong("timestamp"),
                        isOutbound = obj.getBoolean("isOutbound")
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun keyFor(pubKey: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pubKey.toByteArray())
        return "msgs_" + bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
