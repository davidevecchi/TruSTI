package com.davv.trusti.model

data class Message(
    val id: String,
    val contactPublicKey: String,
    val content: String,
    val timestamp: Long,
    val isOutbound: Boolean
)
