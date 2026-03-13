package com.davv.trusti.smp

import java.security.MessageDigest

/** SHA-256 of [input], hex-encoded. */
internal fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Stable room shared by two peers, independent of who initiates. */
internal fun deriveRoomId(pk1: String, pk2: String): String =
    sha256Hex(listOf(pk1, pk2).sorted().joinToString(""))
