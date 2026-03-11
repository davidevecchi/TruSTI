package com.davv.trusti.model

data class Contact(
    val name: String,
    val publicKey: String,       // base64url-encoded EC P-256 public key (X.509 SubjectPublicKeyInfo)
    val lastSeen: Long,          // System.currentTimeMillis()
    val disambiguation: String? = null
)
