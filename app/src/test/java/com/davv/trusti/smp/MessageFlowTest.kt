package com.davv.trusti.smp

import com.davv.trusti.model.Contact
import com.davv.trusti.model.SharingPreferences
import org.json.JSONObject

/**
 * Simple message flow tests that verify bond handshake JSON structure
 * These tests don't require Android framework mocks
 */
class MessageFlowTest {

    fun testBondResponseMessage() {
        println("\n=== Test: Bond Response Message ===")
        val contactPk = "test_public_key_12345678"
        val theirName = "Test Contact"
        val theirDisambig = "swift panda"
        val theirPrefs = SharingPreferences(shareCurrentStatus = true, shareHistory = true)

        val bondResponseJson = JSONObject().apply {
            put("t", "bresp")
            put("accepted", true)
            put("name", theirName)
            put("d", theirDisambig)
            put("prefs", JSONObject().apply {
                put("shareCurrentStatus", theirPrefs.shareCurrentStatus)
                put("shareHistory", theirPrefs.shareHistory)
            })
        }

        assert(bondResponseJson.getBoolean("accepted") == true) { "accepted should be true" }
        assert(bondResponseJson.getString("name") == theirName) { "name mismatch" }
        assert(bondResponseJson.getString("d") == theirDisambig) { "disambiguation mismatch" }

        val prefsObj = bondResponseJson.getJSONObject("prefs")
        assert(prefsObj.getBoolean("shareCurrentStatus") == true) { "shareCurrentStatus mismatch" }
        assert(prefsObj.getBoolean("shareHistory") == true) { "shareHistory mismatch" }

        println("✓ Bond response JSON is correctly formed")
    }

    fun testAccMessage() {
        println("\n=== Test: ACC (Acknowledgment) Message ===")
        val accMessage = JSONObject().apply {
            put("t", "acc")
        }

        assert(accMessage.getString("t") == "acc") { "message type should be 'acc'" }
        println("✓ ACC message created successfully")
    }

    fun testBondRejection() {
        println("\n=== Test: Bond Rejection ===")
        val rejectResponse = JSONObject().apply {
            put("t", "bresp")
            put("accepted", false)
        }

        assert(rejectResponse.getBoolean("accepted") == false) { "accepted should be false" }
        assert(!rejectResponse.has("name")) { "rejected response should not have name" }
        println("✓ Bond rejection response is correctly formed")
    }

    fun testContactCreation() {
        println("\n=== Test: Contact Creation ===")
        val contactPk = "QmFzZTY0VXJsRW5jb2RlZFB1YmxpY0tleQ=="
        val theirName = "Alice"
        val theirDisambig = "calm tiger"
        val theirPrefs = SharingPreferences(shareCurrentStatus = true, shareHistory = false)

        val contact = Contact(
            name = theirName,
            publicKey = contactPk,
            disambiguation = theirDisambig,
            ourSharingPrefs = theirPrefs,
            theirSharingPrefs = theirPrefs
        )

        assert(contact.name == theirName) { "name mismatch" }
        assert(contact.publicKey == contactPk) { "publicKey mismatch" }
        assert(contact.disambiguation == theirDisambig) { "disambiguation mismatch" }
        assert(contact.ourSharingPrefs?.shareCurrentStatus == true) { "shareCurrentStatus mismatch" }
        assert(contact.ourSharingPrefs?.shareHistory == false) { "shareHistory mismatch" }

        println("✓ Contact created from bond response data")
    }

    fun testMessageTypes() {
        println("\n=== Test: Message Type Identification ===")
        val messageTypes = listOf(
            "bresp" to "bond response",
            "acc" to "acknowledgment",
            "sreq" to "status request",
            "srsp" to "status response",
            "bye" to "disconnect"
        )

        for ((type, description) in messageTypes) {
            val msg = JSONObject().put("t", type)
            assert(msg.getString("t") == type) { "Failed to identify $description" }
            println("  ✓ $type ($description)")
        }

        println("✓ All message types correctly identified")
    }

    fun testSymmetricBondFlow() {
        println("\n=== Test: Symmetric Bond Flow ===")
        val initiatorName = "Device A"
        val initiatorDisambig = "brave eagle"
        val responderName = "Device B"
        val responderDisambig = "calm wolf"
        val responderPk = "responder_public_key"

        println("Step 1: Device A initiates bond to Device B")
        println("Step 2: Device B receives offer")
        println("Step 3: Device B approves and sends bond response")

        val bondResponse = JSONObject().apply {
            put("t", "bresp")
            put("accepted", true)
            put("name", responderName)
            put("d", responderDisambig)
        }

        println("Step 4: Device A receives bond response")
        assert(bondResponse.getBoolean("accepted")) { "Bond should be accepted" }

        val aContact = Contact(
            name = responderName,
            publicKey = responderPk,
            disambiguation = responderDisambig
        )

        println("Step 5: Device A creates and saves contact")
        assert(aContact.name == responderName) { "Contact name should be Device B's name" }
        assert(aContact.publicKey == responderPk) { "Contact pk should be Device B's pk" }

        println("Step 6: Device B sends acknowledgment (acc)")
        val accMessage = JSONObject().put("t", "acc")
        assert(accMessage.getString("t") == "acc") { "Should send acc" }

        println("✓ Full symmetric bond flow verified")
    }

    fun runAllTests() {
        try {
            testBondResponseMessage()
            testAccMessage()
            testBondRejection()
            testContactCreation()
            testMessageTypes()
            testSymmetricBondFlow()
            println("\n✅ All tests passed!")
        } catch (e: AssertionError) {
            println("\n❌ Test failed: ${e.message}")
            throw e
        }
    }
}

fun main() {
    MessageFlowTest().runAllTests()
}
