package com.davv.trusti.model

data class Contact(
    val name: String,
    val publicKey: String,       // base64url-encoded EC P-256 public key (X.509 SubjectPublicKeyInfo)
    val disambiguation: String? = null,
    val isConnected: Boolean = false,
    val diseaseStatus: DiseaseStatus? = null
)

data class DiseaseStatus(
    val positiveCount: Int = 0,
    val negativeCount: Int = 0,
    val vaccinatedCount: Int = 0,
    val takeCareCount: Int = 0,
    val notTestedCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val hasPositive: Boolean = false
)
