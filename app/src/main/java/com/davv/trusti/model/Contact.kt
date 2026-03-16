package com.davv.trusti.model

data class Contact(
    val name: String,
    val publicKey: String,
    val disambiguation: String? = null,
    val isConnected: Boolean = false,
    val diseaseStatus: DiseaseStatus? = null,
    val ourSharingPrefs: SharingPreferences? = null,
    val theirSharingPrefs: SharingPreferences? = null
)
