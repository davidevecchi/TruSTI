package com.davv.trusti.utils

import android.content.Context
import com.davv.trusti.model.Contact
import com.davv.trusti.model.DiseaseStatus
import com.davv.trusti.model.SharingPreferences
import org.json.JSONArray
import org.json.JSONObject

object ContactStore {
    private const val PREFS = "trusti_contacts"
    private const val KEY = "contacts_json"

    fun load(context: Context): List<Contact> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
    }

    fun save(context: Context, contact: Contact) {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.publicKey == contact.publicKey }
        if (idx >= 0) list[idx] = contact else list.add(contact)
        persist(context, list)
    }

    fun delete(context: Context, publicKey: String) {
        persist(context, load(context).filter { it.publicKey != publicKey })
    }

    private fun persist(context: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(c: Contact) = JSONObject().apply {
        put("name", c.name)
        put("publicKey", c.publicKey)
        put("disambiguation", c.disambiguation ?: "")
        c.diseaseStatus?.let { s ->
            put("diseaseStatus", JSONObject().apply {
                put("hasPositive", s.hasPositive)
                put("lastUpdated", s.lastUpdated)
            })
        }
        c.ourSharingPrefs?.let { p ->
            put("ourSharingPrefs", sharingToJson(p))
        }
        c.theirSharingPrefs?.let { p ->
            put("theirSharingPrefs", sharingToJson(p))
        }
    }

    private fun fromJson(o: JSONObject): Contact {
        val ds = o.optJSONObject("diseaseStatus")?.let {
            DiseaseStatus(it.optBoolean("hasPositive"), it.optLong("lastUpdated", System.currentTimeMillis()))
        }
        return Contact(
            name = o.optString("name", ""),
            publicKey = o.getString("publicKey"),
            disambiguation = o.optString("disambiguation", "").ifEmpty { null },
            diseaseStatus = ds,
            ourSharingPrefs = o.optJSONObject("ourSharingPrefs")?.let { sharingFromJson(it) },
            theirSharingPrefs = o.optJSONObject("theirSharingPrefs")?.let { sharingFromJson(it) }
        )
    }

    private fun sharingToJson(p: SharingPreferences) = JSONObject().apply {
        put("shareCurrentStatus", p.shareCurrentStatus)
        put("shareHistory", p.shareHistory)
        put("shareCounter", p.shareCounter)
        put("shareVaccines", p.shareVaccines)
    }

    private fun sharingFromJson(o: JSONObject) = SharingPreferences(
        shareCurrentStatus = o.optBoolean("shareCurrentStatus", true),
        shareHistory = o.optBoolean("shareHistory", true),
        shareCounter = o.optBoolean("shareCounter", true),
        shareVaccines = o.optBoolean("shareVaccines", true)
    )
}
