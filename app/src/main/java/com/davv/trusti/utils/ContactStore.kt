package com.davv.trusti.utils

import android.content.Context
import com.davv.trusti.model.Contact
import org.json.JSONArray
import org.json.JSONObject

object ContactStore {

    private const val PREFS = "trusti_contacts"
    private const val KEY = "contacts"
    private const val MAX_CONTACTS = 50

    fun save(context: Context, contact: Contact) {
        val list = load(context).toMutableList()
        list.removeAll { it.publicKey == contact.publicKey }
        list.add(0, contact)
        saveList(context, list)
    }

    fun delete(context: Context, publicKey: String) {
        val list = load(context).toMutableList()
        list.removeAll { it.publicKey == publicKey }
        saveList(context, list)
    }

    private fun saveList(context: Context, list: List<Contact>) {
        val json = JSONArray(list.take(MAX_CONTACTS).map { c ->
            JSONObject().apply {
                put("name", c.name)
                put("publicKey", c.publicKey)
                put("lastSeen", c.lastSeen)
                put("disambig", c.disambiguation ?: "")
            }
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): List<Contact> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { obj ->
                    Contact(
                        name = obj.getString("name"),
                        publicKey = obj.getString("publicKey"),
                        lastSeen = obj.getLong("lastSeen"),
                        disambiguation = obj.optString("disambig").takeIf { it.isNotEmpty() }
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
