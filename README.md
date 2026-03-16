# TruSTI

A privacy-first Android app for sharing STI test results with trusted contacts. Two people exchange a QR code once; after that they can share encrypted health status updates peer-to-peer, with no server ever seeing message content.

**Architecture Overview:**

```mermaid
graph TB
    QR["🔳 QR Code<br/>trusti://peer?pk=B_pub"]
    QR -->|Scan| A_Key

    subgraph DevA["Device A"]
        A_Key["🔑 EC P-256 Key<br/>A_pub, A_priv"]
        A_Store["💾 SharedPrefs<br/>Contacts, Tests"]
        A_Messenger["📨 P2PMessenger"]
        A_Encrypt["🔐 Encryption<br/>ECDH+AES-256-GCM"]
        A_WebRTC["📡 WebRTC DataChannel"]
    end

    Tracker["🌐 WebTorrent Tracker<br/>wss://tracker.ow.com"]
    STUN["🧭 STUN/TURN<br/>stun.l.google.com"]

    subgraph DevB["Device B"]
        B_Key["🔑 EC P-256 Key<br/>B_pub, B_priv"]
        B_Store["💾 SharedPrefs<br/>Contacts, Tests"]
        B_Messenger["📨 P2PMessenger"]
        B_Encrypt["🔐 Encryption<br/>ECDH+AES-256-GCM"]
        B_WebRTC["📡 WebRTC DataChannel"]
    end

    A_Key --> A_Messenger
    B_Key --> B_Messenger

    A_Store -.->|Load/Save| A_Messenger
    B_Store -.->|Load/Save| B_Messenger

    A_Messenger -->|Plaintext| A_Encrypt
    B_Messenger -->|Plaintext| B_Encrypt

    A_Encrypt -->|Ciphertext| A_WebRTC
    B_Encrypt -->|Ciphertext| B_WebRTC

    A_WebRTC -->|SDP Offer/Answer| Tracker
    B_WebRTC -->|SDP Offer/Answer| Tracker
    Tracker -->|Route| A_WebRTC
    Tracker -->|Route| B_WebRTC

    A_WebRTC -.->|ICE Candidates| STUN
    B_WebRTC -.->|ICE Candidates| STUN

    A_WebRTC <-->|P2P Encrypted<br/>Messages| B_WebRTC

    style DevA fill:#ffffff00,stroke:#0080ff,color:#000,stroke-width:2px
    style DevB fill:#ffffff00,stroke:#0080ff,color:#000,stroke-width:2px
    style Infra fill:#ffffff00,stroke:#ff0000,color:#000,stroke-width:2px

    style A_Key fill:#00ff0066,stroke:#00ff00,color:#000,stroke-width:2px
    style A_Messenger fill:#0080ff66,stroke:#0080ff,color:#000,stroke-width:2px
    style A_Encrypt fill:#ff00ff66,stroke:#ff00ff,color:#000,stroke-width:2px
    style A_WebRTC fill:#ffaa0066,stroke:#ffaa00,color:#000,stroke-width:2px

    style B_Key fill:#00ff0066,stroke:#00ff00,color:#000,stroke-width:2px
    style B_Messenger fill:#0080ff66,stroke:#0080ff,color:#000,stroke-width:2px
    style B_Encrypt fill:#ff00ff66,stroke:#ff00ff,color:#000,stroke-width:2px
    style B_WebRTC fill:#ffaa0066,stroke:#ffaa00,color:#000,stroke-width:2px

    style Tracker fill:#ff000066,stroke:#ff0000,color:#000,stroke-width:2px
    style STUN fill:#ffff0066,stroke:#ffff00,color:#000,stroke-width:2px
    style QR fill:#00ffff66,stroke:#00ffff,color:#000,stroke-width:2px
```

---

## Core Principles

1. **No central server:** Keys, encryption, messaging happen on-device or directly P2P
2. **Tracker = rendezvous only:** Routes SDP signaling; never stores/relays content
3. **One QR scan:** After first handshake, reconnects happen automatically via permanent room
4. **Ephemeral encryption:** Each message has its own key; past messages stay safe even if key stolen

---

## How It Works

### 1. Identity & Key Exchange

Every user has a permanent EC P-256 key pair generated on first launch and stored in SharedPreferences (`crypto/KeyManager.kt`). The public key is the user's identity — there is no account or server.

Adding a contact: scan their QR code. The QR encodes:
```
trusti://peer?pk=<BASE64URL_PUBKEY>
```

**QR Exchange Diagram:**
```mermaid
sequenceDiagram
    participant A as Device A
    participant B as Device B
    Note over B: Display own QR<br/>QR = trusti://peer?pk=B_pub
    Note over A: Scan B's QR
    A->>A: Extract B_pub
    A->>A: Compute room = sha256(sorted(A_pub + B_pub))
    Note over A: Derive shared room<br/>Store B as contact
    Note over B: Waiting to be scanned...
    B->>Tracker: Announce in sha256(B_pub)
    Note over A: Later: scan triggers handshake
```

With B's public key (B_pub), A can:
- Derive permanent signaling room: `sha256(sorted(A_pub || B_pub))`
- Encrypt messages so only B can decrypt

### 2. Signaling via WebTorrent Tracker

Peers discover and exchange signaling through a public WebTorrent tracker (`wss://tracker.openwebtorrent.com`). **The tracker routes only encrypted SDP offers/answers — never message content.**

**Room Types (all SHA-256 hashed):**

| Room Type | Key | Purpose |
| --- | --- | --- |
| **Personal** | `sha256(my_public_key)` | I listen here; new peers reach me after scanning my QR |
| **Permanent** | `sha256(sorted(A_pub \|\| B_pub))` | Both peers announce here; enables reconnection without scanning |

**Room Hashing:** Keys are concatenated lexicographically then hashed. Example: if A_pub < B_pub (byte comparison), then room = sha256(A_pub || B_pub). Both peers compute the same room ID independently.

#### First-Time Connection Flow (A scans B's QR)

```mermaid
sequenceDiagram
    participant A as A<br/>(Offerer)
    participant ICE_A as STUN/TURN<br/>stun.l.google.com
    participant Tracker as WebTorrent Tracker
    participant ICE_B as STUN/TURN<br/>stun.l.google.com
    participant B as B<br/>(Answerer)

    B->>Tracker: announce ✓ in sha256(B_pub)
    Note over A: Scans QR → gets B_pub

    rect rgb(200,220,255)
    Note over A,B: ICE Gathering (vanilla)
    A->>ICE_A: gather candidates
    B->>ICE_B: gather candidates
    end

    A->>Tracker: announce + offer<br/>SDP with all ICE candidates<br/>to sha256(B_pub)
    Tracker->>B: deliver offer

    B->>Tracker: sendAnswer<br/>SDP with all ICE candidates
    Tracker->>A: deliver answer

    rect rgb(200,255,200)
    Note over A,B: WebRTC Connectivity
    A->>B: ICE checks (direct P2P)
    B->>A: ICE checks (direct P2P)
    Note over A,B: DataChannel open
    end

    A->>Tracker: announce in perm_room
    B->>Tracker: announce in perm_room

    Note over A,B: Bonded ✓<br/>Can now send encrypted messages
```

**Key point:** ICE candidates are bundled in the SDP (vanilla ICE), not trickled separately—required because the tracker only understands announce/answer messages.

**After bonding:** Both peers derive and announce in the permanent room. Reconnects happen without QR scanning.

#### Reconnection Flow (both peers have each other saved)

```mermaid
sequenceDiagram
    participant A as A
    participant Tracker as WebTorrent Tracker
    participant B as B

    Note over A,B: On app launch:<br/>both announce in permanent room

    A->>Tracker: announce in perm_room
    B->>Tracker: announce in perm_room
    Tracker->>A: peer list (includes B)
    Tracker->>B: peer list (includes A)

    rect rgb(200,220,255)
    Note over A: First to initiate<br/>sends offer
    A->>Tracker: announce + offer<br/>SDP + ICE candidates<br/>in perm_room
    Tracker->>B: deliver offer
    end

    rect rgb(200,220,255)
    B->>Tracker: sendAnswer<br/>SDP + ICE candidates
    Tracker->>A: deliver answer
    end

    rect rgb(200,255,200)
    Note over A,B: ICE connectivity → DataChannel
    Note over A,B: Ready for encrypted messages
    end
```

#### Participant Roles

**A (Offerer / Initiator):**
- Has B's public key (scanned QR or saved contact)
- Initiates handshake by creating WebRTC offer
- Gathers ICE candidates locally
- Sends offer + all candidates to tracker in one message

**Tracker (Rendezvous Point):**
- Routes WebRTC signaling only—never sees plaintext
- Peers announce to enter a "room"
- Delivers offer/answer between A and B via announce protocol
- **No storage, no relay:** stateless routing

**B (Answerer / Listener):**
- Listens in personal room: `sha256(B_pub)`
- Receives A's offer from tracker
- Gathers own ICE candidates
- Sends answer + candidates back via tracker
- Both devices form direct DataChannel

#### Signaling Message Flow

```mermaid
sequenceDiagram
    participant A as A (Offerer)
    participant T as Tracker<br/>(routing only)
    participant B as B (Answerer)

    Note over A,B: Room = sha256(B_pub)

    A->>T: announceWithOffer<br/>SDP + ICE candidates<br/>+ identity attrs
    T->>B: [deliver offer]

    B->>T: sendAnswer<br/>SDP + ICE candidates
    T->>A: [deliver answer]

    Note over A,B: P2P connection<br/>established
```

**Identity:** Public key + display name in SDP as `a=x-trusti-*` attributes—B knows who called without needing a server.

### 3. WebRTC Data Channel (Vanilla ICE)

Once signaling completes, a direct P2P encrypted channel opens between devices (`smp/WebRtcTransport.kt`).

**Vanilla ICE (Complete Mode):**
- All ICE candidates gathered **before** sending SDP
- Bundled into offer/answer at once
- **Why:** WebTorrent tracker only understands announce/answer protocol, not trickle ICE messages

```mermaid
graph LR
    A["A gathers<br/>ICE candidates"]
    SDP1["SDP Offer<br/>+ all candidates"]
    T["Tracker<br/>routes"]
    SDP2["SDP Answer<br/>+ all candidates"]
    B["B gathers<br/>ICE candidates"]
    P2P["P2P<br/>DataChannel"]

    A --> SDP1 --> T --> B
    B --> SDP2 --> T --> A
    A --> P2P
    B --> P2P

    style P2P fill:#90EE90
    style T fill:#87CEEB
```

**NAT Traversal:**
- Google STUN servers: public IP + port discovery
- OpenRelay TURN: fallback for symmetric NAT (bandwidth relay)

**Auto-initiate:** If offline when message sent, handshake triggers automatically; message queued and delivered on open.

### 4. End-to-End Encryption

Every message is encrypted **before** handing to WebRTC. Even if captured, content is unreadable without the recipient's private key.

**Algorithm: ECDH (ephemeral) + AES-256-GCM** (`smp/Encryption.kt`)

**Sender (A) encrypts for recipient (B):**
```
plaintext → [gen ephemeral key pair]
         → [ECDH: ephemeral_priv XOR B_pub]
         → [derive AES-256 key via SHA-256]
         → [gen random 12-byte IV]
         → [AES-256-GCM encrypt + auth tag]
         → wire format: [ephPubLen|ephPubDER|IV|ciphertext|tag]
```

**Recipient (B) decrypts:**
```
wire format → [parse ephemeral_pub, IV, ciphertext]
           → [ECDH: B_priv XOR ephemeral_pub]
           → [derive same AES-256 key via SHA-256]
           → [AES-256-GCM decrypt + verify auth tag]
           → plaintext (or ❌ fail if tampered)
```

**Ephemeral key per message:** No long-term shared secret; no key reuse. Past messages stay safe even if long-term key is compromised (forward secrecy).

### 5. Message Types

All messages are JSON (then encrypted). Three types:

| Type | Payload | Purpose |
| --- | --- | --- |
| `text` | `{from, content, ts}` | Chat message |
| `status_request` | `{from}` | Ask peer: do you have a positive test? |
| `status_response` | `{from, hasPositive, queuedAt?}` | Reply: yes/no + delivery timestamp |

**Status Delivery:** If B is offline when A sends a status update, A stores it in `PendingStatusStore`. When B reconnects, the status is delivered atomically (read-once, then sent). Only the latest status per contact is kept—no backlog.

---

## Storage and Caching

### Persistent storage (survives process death)

All persistent state lives in Android `SharedPreferences` as JSON strings — no database, no files.

| Store                | Prefs key               | Contents                                                          | Notes                                                                           |
| -------              | -----------             | ----------                                                        | -------                                                                         |
| `KeyManager`         | `trusti_keys`           | EC P-256 key pair (DER-encoded)                                   | Generated once on first launch; never leaves the device                         |
| `ContactStore`       | `trusti_contacts`       | List of up to 50 contacts (name, public key, last disease status) | `isConnected` is always written as `false` — it is a runtime-only flag          |
| `TestsStore`         | `trusti_tests`          | Medical records (disease, date, POSITIVE / NEGATIVE)              | Read on every incoming `sreq` to compute the current positive flag              |
| `PendingStatusStore` | `trusti_pending_status` | One queued status update per offline contact                      | Entries expire after 7 days; consumed atomically when the contact next connects |
| `ProfileManager`     | `trusti_profile`        | Display name + disambiguation suffix                              | Set once during onboarding                                                      |

### In-session state (cleared on process death)

`P2PMessenger` is a process-lifetime singleton. The following maps live purely in memory and are rebuilt from scratch on every app launch:

| Field                      | Type                                     | Purpose                                                                                              |
| -------                    | ------                                   | ---------                                                                                            |
| `transports`               | `ConcurrentHashMap<pk, WebRtcTransport>` | One active DataChannel per connected peer                                                            |
| `pendingOffers`            | `ConcurrentHashMap<offerId, pk>`         | Tracks offers A sent so incoming answers can be matched                                              |
| `pendingApproval`          | `Set<pk>`                                | Peers whose incoming-request dialog has not been answered yet                                        |
| `pendingHandshakes`        | `Queue<Contact>`                         | Handshakes queued before the tracker WebSocket connected                                             |
| `retryJobs`                | `ConcurrentHashMap<pk, Job>`             | Active offer-retry coroutines (re-announce every 5 s, up to 6 times)                                 |
| `newlyBondedContacts`      | `Set<pk>`                                | Peers that bonded in this session — drives the "new bond" confirmation dialog                        |
| `handledOffers`            | `ConcurrentHashMap<pk, offerId>`         | Dedup cache: the last offer ID processed per peer; capped at 100 entries to prevent unbounded growth |
| `pendingAccepts`           | `Set<pk>`                                | B-side: approved contacts waiting for the DataChannel to open before sending `acc`                   |
| `isConnected` on `Contact` | `Boolean`                                | Set to `true` in memory when a transport opens; always `false` when loaded from disk                 |

### Startup Sequence

```mermaid
sequenceDiagram
    participant App as App Launch
    participant KeyMgr as KeyManager
    participant SharedPref as SharedPreferences
    participant Messenger as P2PMessenger
    participant Tracker as WebTorrent Tracker

    App->>KeyMgr: Initialize
    KeyMgr->>SharedPref: Load/generate EC P-256 keypair
    SharedPref-->>KeyMgr: my_pub, my_priv

    App->>Messenger: initialize()
    Messenger->>Tracker: Connect WebSocket<br/>wss://tracker.openwebtorrent.com

    Messenger->>Messenger: Load saved contacts<br/>from SharedPreferences

    Tracker-->>Messenger: Connected ✓

    rect rgb(220,240,255)
    Note over Messenger,Tracker: Announce in personal room
    Messenger->>Tracker: announce<br/>room = sha256(my_pub)
    end

    rect rgb(220,240,255)
    Note over Messenger,Tracker: Announce in permanent rooms
    Messenger->>Tracker: announce in perm_room<br/>for each saved contact
    end

    Note over Messenger: Ready for incoming QR scans<br/>& contact reconnections
```

**State after startup:**
- All contacts have `isConnected = false` (memory only)
- Personal room active → can receive incoming scans
- Permanent rooms active → can receive peer announcements for existing bonds

### Pending status delivery

When a test result changes and a contact is offline, the latest status is written to `PendingStatusStore`. The existing entry for that contact is replaced (not appended), so only the most recent status is ever queued. When the contact's DataChannel opens, `deliverPendingStatus()` atomically reads and removes the entry, then sends it over the encrypted channel.

---

## Privacy Properties

**What the tracker sees:**
- Hashed room IDs (sha256 values—cannot be reversed)
- SDP offer/answer (connection handshake only—no content)
- Peer announcements (time + room—no metadata)

**What the tracker does NOT see:**
- Message content (encrypted end-to-end)
- Real identities (only public key hashes)
- Contact relationships (different per pair)
- Test results, health data, anything about users

```mermaid
sequenceDiagram
    participant A as A (Device)
    participant T as Tracker
    participant B as B (Device)

    A->>A: Plaintext message
    A->>A: Encrypt with B_pub
    A->>T: Send (hashed room + encrypted bytes)

    Note over T: Tracker sees:<br/>✗ Message content<br/>✗ Real identities<br/>✓ Only encrypted blobs<br/>& room hashes

    T->>B: Deliver encrypted bytes

    B->>B: Decrypt with B_priv
    B->>B: Read plaintext
```

| Property | How achieved |
| --- | --- |
| **No server stores messages** | End-to-end encrypted; only encrypted bytes route through tracker |
| **No server knows identity** | Keys generated locally; tracker sees only room hashes |
| **Forward secrecy** | Ephemeral key per message—past intercepts unreadable even if long-term key stolen |
| **Authenticated encryption** | AES-GCM tag; tampering detected immediately (fail-safe) |
| **Contact privacy** | Different room per pair; tracker can't link contacts together |

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
