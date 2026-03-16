# Test Guide: Bond Flow & Contact Saving

This guide explains the tests created to debug why Device A wasn't saving contacts.

## Issues Found & Fixed

1. **Missing `sendAcc()` calls** - The acknowledgment message was removed during refactoring
   - Fixed: Added `sendAcc()` calls in `approveIncomingRequest()` (line 162 in P2PMessenger.kt)
   - Fixed: Added deferred `sendAcc()` when transport opens (line 442 in P2PMessenger.kt)

2. **Missing handler for "acc" message** - Device A didn't process the acknowledgment
   - Fixed: Added handler at line 473 in P2PMessenger.kt

## Tests Overview

### Unit Tests (Local, no device needed)
**File:** `app/src/test/java/com/davv/trusti/smp/MessageFlowTest.kt`

Tests the JSON message format and data flow logic:
- `testBondResponseMessage()` - Bond response structure
- `testAccMessage()` - Acknowledgment message creation
- `testBondRejection()` - Rejection response format
- `testContactCreation()` - Contact object creation from response
- `testMessageTypes()` - All message types identified correctly
- `testSymmetricBondFlow()` - Full bond handshake sequence

**Run:**
```bash
./gradlew test
```

### Instrumented Tests (Requires device/emulator)
**File:** `app/src/androidTest/java/com/davv/trusti/BondFlowInstrumentedTest.kt`

Tests the actual ContactStore persistence with real Android SharedPreferences:
- `testContactStoreBasicSaveAndLoad()` - Save and load single contact
- `testContactStoreMultipleSave()` - Save multiple contacts
- `testContactStoreWithSharingPreferences()` - Preserve sharing preferences
- `testBondResponseScenario()` - **Device A receives bond response and saves**
- `testResponderApprovalScenario()` - **Device B approves and saves**
- `testSymmetricBondFlow()` - **Both devices save (the full scenario)**
- `testContactDelete()` - Delete contact
- `testContactUpdateWithNewValues()` - Update existing contact
- `testContactMaxLimit()` - Enforce 50-contact max

**Run on connected device/emulator:**
```bash
./gradlew connectedAndroidTest
```

Or on a specific device:
```bash
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunner=androidx.test.runner.AndroidJUnitRunner
```

## Debug Logging Feature

You can now see all messages sent/received as toasts:

1. Go to **Settings** tab
2. Toggle **"Log Messages (Toast)"** ON
3. All bond handshake messages appear as toasts with sender/message type

Example toast messages:
- `→ bresp (abc12345) accept` - Sending bond acceptance
- `← bresp (abc12345) accept` - Receiving bond acceptance
- `→ acc (abc12345) confirmed` - Sending acknowledgment
- `← acc (abc12345) bond confirmed` - Receiving acknowledgment

## Bond Handshake Flow

Both directions should now work symmetrically:

### Device A initiates (scans B's QR):
```
1. A → B: offer (WebRTC SDP in handshake room)
2. B ← A: receive offer
3. B: user approves in dialog
4. B → A: bresp "accepted=true"
5. B → A: acc "acknowledged"
6. A ← B: receive bresp → SAVE CONTACT ✓
7. A ← B: receive acc → log confirmed
```

### Device B receives (scanned by A):
```
1. A → B: offer
2. B ← A: receive offer → emit dialog event
3. B: user approves
4. B → A: bresp "accepted=true" → SAVE CONTACT ✓
5. B → A: acc "acknowledged"
6. A receives and processes
```

## What Tests Verify

✓ Message structure is correct (bresp, acc, sreq, srsp, bye)
✓ Contact creation preserves all fields
✓ ContactStore saves and loads correctly
✓ Sharing preferences are preserved
✓ Both devices can save contacts symmetrically
✓ Max contact limit (50) is enforced
✓ Contact update/delete work correctly

## Debugging Checklist

If tests fail or contacts still aren't saving:

1. **Run unit tests:**
   ```bash
   ./gradlew test -i
   ```
   Check if message structure is correct

2. **Run instrumented tests:**
   ```bash
   ./gradlew connectedAndroidTest -i
   ```
   Check if ContactStore actually saves/loads

3. **Enable message logging:**
   - Settings → Toggle "Log Messages (Toast)"
   - Watch the toasts during bond
   - Should see: `→ bresp accept` → `← bresp accept` → `→ acc confirmed` → `← acc confirmed`

4. **Check logs:**
   ```bash
   adb logcat | grep "P2PMessenger\|ContactStore"
   ```

5. **Common issues:**
   - Transport not open when approving → handled by `pendingAccepts` queue
   - Message not received → check signaling/WebRTC connection
   - Contact saved but not visible → check that ContactsFragment reloads on ChannelOpened event

## File Changes

- `DebugSettings.kt` - New: Debug preference manager
- `SettingsFragment.kt` - Modified: Added debug logging toggle
- `P2PMessenger.kt` - Modified: Added `sendAcc()` calls and message logging
- `app/src/test/java/com/davv/trusti/smp/MessageFlowTest.kt` - New: Unit tests
- `app/src/androidTest/java/com/davv/trusti/BondFlowInstrumentedTest.kt` - New: Integration tests
