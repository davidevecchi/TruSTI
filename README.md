# TruSTI

A private, end-to-end encrypted Android messenger that uses **QR codes for identity bootstrapping** and **WebRTC for P2P message delivery** — inspired by the Chitchatter privacy model.

No phone numbers. No account registration. No server-side identity. No custom server required.

---

## Architecture

The app is split into four layers:

**UI** — `MainActivity` hosts fragments: `HomeFragment` (shows your QR, opens the scanner), `ContactsFragment` (list of saved contacts), and `SettingsFragment` (theme settings). Tapping a contact opens `ConversationActivity`, a RecyclerView chat screen.

**Messaging** — `P2PMessenger` is a singleton that manages P2P connections. It exposes `initialize()`, `sendMessage()`, and a `messageFlow` SharedFlow that the UI observes for incoming messages.

**P2P / Transport** — `WebRtcTransport` manages RTCPeerConnection per contact. `TorrentSignaling` uses public WebTorrent trackers for exchanging WebRTC offers/answers. `Encryption` performs ECDH-ephemeral + AES-256-GCM on every payload.

**Crypto** — `KeyManager` generates and persists an EC P-256 key pair. `QrHelper` encodes/decodes the `trusti://` URI.

**Storage** — `ContactStore` and `MessageStore` persist contacts and messages as JSON in `SharedPreferences`.

---

## Key Exchange & Connection Flow

1. On first launch, each device generates an EC P-256 key pair.
2. The **Home** tab shows a QR code encoding `trusti://peer?pk=PUBKEY`.
3. When a peer scans the QR:
   - Both parties derive a shared **Room ID** = `SHA256(sort(myPubKey, theirPubKey))`.
   - Both connect to a public WebTorrent tracker (e.g., `wss://tracker.openwebtorrent.com`).
   - They announce the Room ID to the tracker to find each other and exchange WebRTC SDP offers/answers.
   - A direct P2P WebRTC data channel is established.
4. Messages flow directly between devices. The tracker never sees the content and is only used for the initial handshake.

---

## Encryption

Every payload is end-to-end encrypted before leaving the device. 

Algorithm: ECDH-ephemeral + AES-256-GCM (on top of WebRTC's built-in DTLS)

Wire format:

    [2 bytes: ephPubLen] [ephPubDER] [12-byte IV] [ciphertext + 16-byte GCM tag]

---

## Project Structure

    app/src/main/java/com/davv/trusti/
    ├── MainActivity.kt              Fragment host; initializes P2PMessenger
    ├── HomeFragment.kt              Shows own QR + Scan Peer button
    ├── ContactsFragment.kt          Contact list; tap → ConversationActivity
    ├── ConversationActivity.kt      RecyclerView chat UI
    ├── SettingsFragment.kt          Theme toggle
    ├── crypto/
    │   └── KeyManager.kt            EC P-256 key pair generation + SharedPreferences storage
    ├── connection/
    │   └── QrHelper.kt              QR bitmap generation + PeerInfo URI parsing
    ├── model/
    │   ├── Contact.kt               name, publicKey, lastSeen
    │   └── Message.kt               id, contactPublicKey, content, timestamp, isOutbound
    ├── utils/
    │   ├── ContactStore.kt          JSON persistence for contacts
    │   └── MessageStore.kt          Per-contact message persistence
    └── smp/
        ├── Encryption.kt            ECDH-ephemeral + AES-256-GCM encrypt/decrypt
        ├── TorrentSignaling.kt      WebRTC signaling via public trackers
        ├── WebRtcTransport.kt       WebRTC DataChannel management
        └── P2PMessenger.kt          High-level P2P messaging logic

---

## Privacy Model

- **Serverless**: No custom servers to run or trust. Uses public infrastructure (trackers/STUN) only for discovery.
- **No accounts**: Identity is a locally generated key pair.
- **No enumeration**: Room IDs are derived from public keys; strangers cannot discover your "room".
- **E2EE**: All messages are encrypted with keys known only to the two parties.
- **P2P**: Messages travel directly between devices whenever possible.
