package com.davv.trusti.utils

import android.content.Context
import java.util.Random

object ProfileManager {
    private const val PREFS = "trusti_profile"
    private const val KEY_USERNAME = "username"
    private const val KEY_DISAMBIGUATION = "disambiguation"

    private val adjectives = listOf(
        "swift", "quiet", "brave", "bright", "calm", "cool", "eager", "fair", "gentle", "happy",
        "kind", "lucky", "mild", "nice", "proud", "pure", "rare", "sharp", "smooth", "soft",
        "tame", "vast", "warm", "wild", "wise", "young", "zesty", "bold", "crisp", "daring"
    )

    private val nouns = listOf(
        "panda", "tiger", "eagle", "falcon", "wolf", "bear", "lion", "deer", "hawk", "shark",
        "whale", "dolphin", "fox", "otter", "seal", "owl", "raven", "crane", "lynx", "puma",
        "jaguar", "leopard", "bison", "moose", "elk", "zebra", "gazelle", "koala", "sloth", "gecko"
    )

    fun getUsername(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, "Anonymous") ?: "Anonymous"
    }

    fun setUsername(context: Context, username: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_USERNAME, username).apply()
    }

    fun getDisambiguation(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var disambiguation = prefs.getString(KEY_DISAMBIGUATION, null)
        if (disambiguation == null) {
            disambiguation = rollDisambiguation(context)
        }
        return disambiguation
    }

    fun rollDisambiguation(context: Context): String {
        val random = Random()
        val adj = adjectives[random.nextInt(adjectives.size)]
        val noun = nouns[random.nextInt(nouns.size)]
        val disambiguation = "$adj-$noun"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISAMBIGUATION, disambiguation).apply()
        return disambiguation
    }
}
