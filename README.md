# TruSTI

A privacy-first Android app for sharing STI test results with trusted contacts. Two people exchange a QR code once; after that they can share encrypted health status updates peer-to-peer, with no server ever seeing message content.

---

## How It Works

### 1. Identity & Key Exchange

Every user has a permanent EC P-256 key pair generated on first launch and stored in SharedPreferences (`crypto/KeyManager.kt`). The public key is the user's identity — there is no account, username, or server registration.

Adding a contact is done by scanning their QR code. The QR encodes a URI:

```
trusti://peer?pk=<BASE64URL_PUBKEY>
```

This gives you their public key, which is all you need to:
- Derive a shared signaling room for reconnection
- Encrypt messages only they can read

### 2. Signaling via WebTorrent Tracker

Peers need to find each other to establish a direct connection. TruSTI uses a public WebTorrent tracker (`wss://tracker.openwebtorrent.com`) as a rendezvous point — the same infrastructure BitTorrent clients use to find peers for a torrent.

**The tracker never sees message content.** It only routes WebRTC signaling messages (SDP offer/answer) between peers.

Two room types are used (both derived with SHA-256):

| Room | Derivation | Purpose |
|------|-----------|---------|
| Handshake room | `sha256(B's public key)` | B listens here so A can reach them after scanning the QR |
| Permanent room | `sha256(sort(A_key + B_key))` | Both peers announce here for reconnection after the first handshake |

On startup, the app announces itself in its own handshake room and in a permanent room for every saved contact.

#### Signaling Message Flow

```
A (offerer)  ──announceWithOffer──▶  Tracker  ──offer──▶  B (answerer)
B (answerer) ──sendAnswer──────────▶  Tracker  ──answer──▶ A (offerer)
                                      ▲
                          (no relay — tracker only routes SDP)
```

Identity (public key, display name) is embedded in the SDP offer as custom `a=x-trusti-*` attributes, so B learns who is calling without needing a directory server.

### 3. WebRTC Data Channel (Vanilla ICE)

Once signaling completes, a WebRTC `RTCPeerConnection` with a `DataChannel` is established directly between the two devices (`smp/WebRtcTransport.kt`).

TruSTI uses **vanilla ICE** (also called "complete ICE"): ICE gathering runs to completion before the SDP is sent, so all candidates are bundled in the SDP itself. This is necessary because the WebTorrent tracker doesn't relay arbitrary peer messages — only the structured announce format. Trickle ICE would require a separate signaling channel.

NAT traversal uses:
- Google STUN (`stun.l.google.com:19302`)
- OpenRelay TURN (fallback when both peers are behind symmetric NAT)

If the peer is offline when a message is sent, the handshake is initiated automatically and the message is delivered as soon as the data channel opens.

### 4. End-to-End Encryption

Every message is encrypted before being handed to WebRTC. Even if the data channel were intercepted, the content would be unreadable without the recipient's private key.

**Algorithm: ECDH ephemeral + AES-256-GCM** (`smp/Encryption.kt`)

```
Encrypt(plaintext, recipientPublicKey):
  1. Generate a fresh ephemeral EC P-256 key pair
  2. ECDH(ephemeral_private, recipient_public) → shared_secret
  3. SHA-256(shared_secret) → 256-bit AES key
  4. Generate 12-byte random IV
  5. AES-256-GCM encrypt(plaintext, key, IV) → ciphertext + 128-bit tag

Wire format:
  [2-byte big-endian ephPubLen][ephPubDER][12-byte IV][ciphertext+GCM-tag]
```

```
Decrypt(data, myPrivateKey):
  1. Parse ephPubLen, ephPubDER, IV, ciphertext
  2. ECDH(my_private, ephemeral_public) → shared_secret
  3. SHA-256(shared_secret) → AES key
  4. AES-256-GCM decrypt(ciphertext, key, IV) → plaintext
     (authentication tag verified; fails loudly on tamper)
```

Each message uses a freshly generated ephemeral key pair, so there is no long-term shared secret and no key reuse across messages.

### 5. Message Types

All messages are JSON, encrypted as described above. Three types are defined:

| `type` | Payload | Purpose |
|--------|---------|---------|
| `text` | `from`, `content`, `ts` | Chat message |
| `status_request` | `from` | Ask the peer for their current test status |
| `status_response` | `from`, `hasPositive`, `queuedAt?` | Reply with whether any test result is positive |

Status responses that can't be delivered immediately (contact offline) are persisted locally in `PendingStatusStore` and sent as soon as the contact next connects.

---

## Privacy Properties

| Property | How it's achieved |
|----------|------------------|
| No server stores messages | Messages travel over an encrypted WebRTC data channel directly between devices |
| No server knows your identity | Your key pair is generated locally; the tracker only sees ephemeral peer IDs |
| Forward secrecy per message | Each encryption uses a fresh ephemeral key — past messages can't be decrypted even if your long-term key is later compromised |
| Authenticated encryption | AES-GCM provides integrity; a tampered message will fail decryption |
| Contact discovery is private | Room IDs are SHA-256 hashes; the tracker cannot reverse them to learn who is talking to whom |

---

## Project Structure

```
app/src/main/java/com/davv/trusti/
├── crypto/
│   └── KeyManager.kt          EC P-256 key pair generation and storage
├── connection/
│   └── QrHelper.kt            QR generation and PeerInfo parsing
├── model/
│   ├── Contact.kt             name, publicKey, lastSeen
│   ├── Message.kt             chat message
│   └── MedicalRecord.kt       test result (disease, date, POSITIVE/NEGATIVE)
├── smp/
│   ├── Encryption.kt          ECDH + AES-256-GCM encrypt/decrypt
│   ├── TorrentSignaling.kt    WebTorrent tracker WebSocket client
│   ├── WebRtcTransport.kt     RTCPeerConnection + DataChannel per contact
│   └── P2PMessenger.kt        Singleton orchestrating signaling, transport, encryption
├── utils/
│   ├── ContactStore.kt        JSON persistence for contacts
│   ├── MessageStore.kt        Per-contact message persistence
│   ├── MedicalStore.kt        JSON persistence for medical records
│   ├── PendingStatusStore.kt  Queued status updates for offline contacts
│   └── ProfileManager.kt      Display name + disambiguation (adjective-noun)
└── ui/
    ├── CommonComponents.kt
    ├── DiseaseTestResult.kt   Disease row with +/−/? chips
    └── DiseaseTestList.kt     List of diseases with results
```

---

## Build

- minSdk 26 (Android 8.0)
- targetSdk / compileSdk 36
- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11+

```bash
./gradlew assembleDebug
```
