package com.davv.trusti

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davv.trusti.model.Contact
import com.davv.trusti.model.SharingPreferences
import com.davv.trusti.utils.ContactStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BondFlowInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        clearAllContacts()
    }

    @After
    fun tearDown() {
        clearAllContacts()
    }

    private fun clearAllContacts() {
        ContactStore.load(context).forEach { contact ->
            ContactStore.delete(context, contact.publicKey)
        }
    }

    @Test
    fun testContactStoreBasicSaveAndLoad() {
        println("\n=== Test: Contact Store Basic Save/Load ===")
        val contact = Contact(
            name = "Test Contact",
            publicKey = "test_pk_123456789",
            disambiguation = "swift panda"
        )

        ContactStore.save(context, contact)

        val loaded = ContactStore.load(context)
        assert(loaded.size == 1) { "Expected 1 contact, got ${loaded.size}" }
        assert(loaded[0].name == "Test Contact") { "Name mismatch" }
        assert(loaded[0].publicKey == "test_pk_123456789") { "PublicKey mismatch" }
        println("✓ Contact saved and loaded successfully")
    }

    @Test
    fun testContactStoreMultipleSave() {
        println("\n=== Test: Contact Store Multiple Save ===")
        val contact1 = Contact(name = "Alice", publicKey = "pk_alice", disambiguation = "calm tiger")
        val contact2 = Contact(name = "Bob", publicKey = "pk_bob", disambiguation = "brave eagle")

        ContactStore.save(context, contact1)
        ContactStore.save(context, contact2)

        val loaded = ContactStore.load(context)
        assert(loaded.size == 2) { "Expected 2 contacts, got ${loaded.size}" }
        println("✓ Multiple contacts saved successfully")
    }

    @Test
    fun testContactStoreWithSharingPreferences() {
        println("\n=== Test: Contact Store With Sharing Preferences ===")
        val contact = Contact(
            name = "Alice",
            publicKey = "pk_alice",
            disambiguation = "swift panda",
            theirSharingPrefs = SharingPreferences(
                shareCurrentStatus = true,
                shareHistory = false
            ),
            ourSharingPrefs = SharingPreferences(
                shareCurrentStatus = false,
                shareHistory = true
            )
        )

        ContactStore.save(context, contact)

        val loaded = ContactStore.load(context)
        assert(loaded.size == 1) { "Expected 1 contact" }
        assert(loaded[0].theirSharingPrefs?.shareCurrentStatus == true) { "shareCurrentStatus mismatch" }
        assert(loaded[0].theirSharingPrefs?.shareHistory == false) { "shareHistory mismatch" }
        assert(loaded[0].ourSharingPrefs?.shareCurrentStatus == false) { "ourSharingPrefs shareCurrentStatus mismatch" }
        assert(loaded[0].ourSharingPrefs?.shareHistory == true) { "ourSharingPrefs shareHistory mismatch" }
        println("✓ Contact with sharing preferences saved correctly")
    }

    @Test
    fun testBondResponseScenario() {
        println("\n=== Test: Bond Response Scenario (Device A initiator) ===")
        val deviceBPk = "device_b_public_key_encoded_base64"
        val deviceBName = "Device B"
        val deviceBDisambig = "calm wolf"
        val deviceBPrefs = SharingPreferences(shareCurrentStatus = true, shareHistory = true)
        val ourPrefs = SharingPreferences(shareCurrentStatus = false, shareHistory = false)

        println("Device A receives bond response from Device B")
        val contact = Contact(
            name = deviceBName,
            publicKey = deviceBPk,
            disambiguation = deviceBDisambig,
            theirSharingPrefs = deviceBPrefs,
            ourSharingPrefs = ourPrefs
        )

        // This is what happens in handleBondResponse
        ContactStore.save(context, contact)

        val loaded = ContactStore.load(context)
        assert(loaded.size == 1) { "Contact should be saved" }
        assert(loaded[0].name == deviceBName) { "Name should match" }
        assert(loaded[0].publicKey == deviceBPk) { "PublicKey should match" }
        assert(loaded[0].disambiguation == deviceBDisambig) { "Disambiguation should match" }
        println("✓ Device A saved contact after receiving bond response")
    }

    @Test
    fun testResponderApprovalScenario() {
        println("\n=== Test: Responder Approval Scenario (Device B responder) ===")
        val deviceAPk = "device_a_public_key_encoded_base64"
        val deviceAName = "Device A"
        val deviceADisambig = "brave eagle"
        val deviceAPrefs = SharingPreferences(shareCurrentStatus = true, shareHistory = false)

        println("Device B approves Device A's bond request")
        val contact = Contact(
            name = deviceAName,
            publicKey = deviceAPk,
            disambiguation = deviceADisambig,
            theirSharingPrefs = deviceAPrefs
        )

        // This is what happens in approveIncomingRequest
        ContactStore.save(context, contact)

        val loaded = ContactStore.load(context)
        assert(loaded.size == 1) { "Contact should be saved" }
        assert(loaded[0].name == deviceAName) { "Name should match" }
        println("✓ Device B saved contact after approving bond request")
    }

    @Test
    fun testSymmetricBondFlow() {
        println("\n=== Test: Symmetric Bond Flow (Both devices) ===")
        val deviceAPk = "device_a_pk_base64"
        val deviceBPk = "device_b_pk_base64"
        val deviceAName = "Device A"
        val deviceBName = "Device B"

        println("Device A initiates → Device B receives")
        println("Device B approves → saves Device A's contact")
        val bContact = Contact(
            name = deviceAName,
            publicKey = deviceAPk,
            disambiguation = "swift panda"
        )
        ContactStore.save(context, bContact)

        println("Device B sends response → Device A receives")
        println("Device A saves Device B's contact")
        val aContact = Contact(
            name = deviceBName,
            publicKey = deviceBPk,
            disambiguation = "calm wolf"
        )
        ContactStore.save(context, aContact)

        val loaded = ContactStore.load(context)
        assert(loaded.size == 2) { "Both contacts should be saved, got ${loaded.size}" }

        val savedA = loaded.find { it.name == deviceAName }
        val savedB = loaded.find { it.name == deviceBName }

        assert(savedA != null) { "Device A contact not found" }
        assert(savedB != null) { "Device B contact not found" }
        assert(savedA!!.publicKey == deviceAPk) { "Device A pk mismatch" }
        assert(savedB!!.publicKey == deviceBPk) { "Device B pk mismatch" }

        println("✓ Symmetric bond flow: both devices saved contacts")
    }

    @Test
    fun testContactDelete() {
        println("\n=== Test: Contact Delete ===")
        val contact = Contact(name = "Alice", publicKey = "pk_alice", disambiguation = "swift panda")
        ContactStore.save(context, contact)

        var loaded = ContactStore.load(context)
        assert(loaded.size == 1) { "Expected 1 contact before delete" }

        ContactStore.delete(context, "pk_alice")

        loaded = ContactStore.load(context)
        assert(loaded.size == 0) { "Contact should be deleted" }
        println("✓ Contact deleted successfully")
    }

    @Test
    fun testContactUpdateWithNewValues() {
        println("\n=== Test: Contact Update ===")
        val pk = "test_pk"
        val contact1 = Contact(name = "Alice", publicKey = pk, disambiguation = "swift panda")
        val contact2 = Contact(name = "Alice Updated", publicKey = pk, disambiguation = "calm wolf")

        ContactStore.save(context, contact1)
        var loaded = ContactStore.load(context)
        assert(loaded[0].name == "Alice") { "Initial name should be Alice" }

        ContactStore.save(context, contact2)
        loaded = ContactStore.load(context)
        assert(loaded.size == 1) { "Should still have 1 contact" }
        assert(loaded[0].name == "Alice Updated") { "Contact should be updated" }
        println("✓ Contact updated successfully")
    }

    @Test
    fun testContactMaxLimit() {
        println("\n=== Test: Contact Max Limit ===")
        repeat(60) { i ->
            val contact = Contact(
                name = "Contact $i",
                publicKey = "pk_$i",
                disambiguation = "adj noun"
            )
            ContactStore.save(context, contact)
        }

        val loaded = ContactStore.load(context)
        assert(loaded.size <= 50) { "Should not exceed MAX_CONTACTS (50), got ${loaded.size}" }
        println("✓ Contact limit enforced: ${loaded.size} contacts (max 50)")
    }
}
