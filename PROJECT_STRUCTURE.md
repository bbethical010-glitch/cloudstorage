# Easy Storage Cloud — Complete Project Structure

> **Version:** 1.0.0 · **Last Updated:** April 2026

A decentralized personal cloud storage system that transforms an Android device into a self-hosted file server accessible from anywhere via WebRTC P2P connections with a relay fallback.

---

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           EASY STORAGE CLOUD                                     │
│                         Three-Layer Architecture                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────┐      WebSocket       ┌─────────────────┐      WebRTC       │
│  │  Android Node   │◄════════════════════►│  Relay Server   │◄══════════════►│  Browser    │
│  │  (Ktor 8080)    │     Signaling +      │  (Render.com)   │     P2P Data    │  (React)   │
│  │  Foreground     │     API Fallback     │  Ktor/Netty     │     Channel     │  SPA       │
│  │  Service        │                      │                 │                 │            │
│  └────────┬────────┘                      └─────────────────┘                 │
│           │                                                                    │
│           │ Storage Access Framework (SAF)                                     │
│           ▼                                                                    │
│  ┌─────────────────┐                                                           │
│  │ External SSD/   │ ← Files never leave this device in P2P mode              │
│  │ Pen Drive       │                                                           │
│  └─────────────────┘                                                           │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Design Philosophy

1. **Phone as Source of Truth**: All files reside on external storage attached to the Android device
2. **Relay for Reachability Only**: The relay provides signaling and fallback proxy, not file storage
3. **P2P First**: WebRTC DataChannels carry file bytes directly between browser and phone
4. **Relay Fallback**: When P2P fails (NAT/firewall), requests proxy over WebSocket tunnel

---

## Repository Structure

```
AndroidCloudStorageApp/
├── CLAUDE.md                          # AI assistant guidance
├── README.md                          # User-facing documentation
├── render.yaml                        # Render deployment blueprint
├── capacitor.config.json              # Capacitor framework configuration
├── build.gradle.kts                   # Root Gradle build configuration
├── settings.gradle.kts                # Gradle module settings
├── package.json                       # Root npm scripts (UI sync)
├── local.properties                   # Build configuration (not committed)
│
├── docs/                              # Technical documentation
│   ├── DESIGN.md                      # System design document
│   ├── relay-architecture.md          # Relay design rationale
│   ├── relay-runbook.md               # Local relay testing guide
│   ├── public-deployment-render.md    # Render deployment guide
│   └── app-links-setup.md             # Android App Links configuration
│
├── app/                               # Android Node Module
│   ├── build.gradle.kts               # Android build config (Ktor, WebRTC, Compose)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # Permissions, services, deep links
│       │   ├── assets/web/            # React SPA bundled into APK
│       │   │   ├── index.html
│       │   │   ├── favicon.svg
│       │   │   └── assets/            # Minified JS/CSS bundles
│       │   ├── java/com/pratham/cloudstorage/
│       │   │   ├── MainActivity.kt           # WebView host + JS bridge
│       │   │   ├── ServerService.kt          # Embedded Ktor HTTP server
│       │   │   ├── RelayTunnelClient.kt      # WebSocket tunnel to relay
│       │   │   ├── WebRTCPeer.kt             # WebRTC P2P connection handler
│       │   │   ├── PeerManager.kt            # Multi-peer connection manager
│       │   │   ├── TransferManager.kt        # Live transfer state (StateFlow)
│       │   │   ├── StreamingUploadProxySession.kt  # Chunked upload state machine
│       │   │   ├── ServerUtils.kt              # MIME types, path helpers
│       │   │   ├── NodeUrlBuilder.kt           # URL construction helpers
│       │   │   ├── UploadNotificationManager.kt # Foreground notifications
│       │   │   ├── PeerRole.kt                 # RBAC role definitions
│       │   │   ├── RemoteVaultViewModel.kt     # P2P client state management
│       │   │   ├── RemoteNodeClient.kt         # P2P client signaling & protocol
│       │   │   ├── P2PClientService.kt         # Background P2P session manager
│       │   │   ├── RemoteVaultScreen.kt        # Native P2P client UI (Compose)
│       │   │   └── ui/theme/
│       │   │       ├── Color.kt                # Compose color palette
│       │   │       ├── Theme.kt                # Material 3 theme
│       │   │       └── Type.kt                 # Typography
│       │   └── res/                   # Android resources
│       └── debug/                     # Debug-only overlays
│
├── relay/                             # Relay Server Module
│   ├── build.gradle.kts               # Ktor/Netty dependencies
│   ├── Dockerfile                     # Multi-stage Docker build
│   ├── settings.gradle.kts            # Standalone module settings
│   └── src/main/
│       ├── kotlin/com/pratham/cloudstorage/relay/
│       │   └── RelayServer.kt         # Signaling + proxy + static hosting
│       └── resources/
│           ├── static/                # React SPA served by relay
│           │   ├── index.html
│           │   ├── favicon.svg
│           │   └── assets/
│           └── web/                   # Alternate asset location
│
├── ui/                                # React Web Console
│   ├── package.json                   # Dependencies + scripts
│   ├── vite.config.ts                 # Vite build configuration
│   ├── index.html                     # HTML entry point
│   ├── public/                        # Static public assets
│   └── src/
│       ├── app/
│       │   ├── App.tsx                # Root component + platform detection
│       │   ├── bridge.ts              # window.Android interface types
│       │   ├── routes.ts              # Hash router configuration
│       │   └── components/
│       │       ├── Root.tsx                    # Router outlet
│       │       ├── LoadingScreen.tsx           # Boot animation
│       │       ├── TransferIndicatorBar.tsx    # Live progress rail
│       │       ├── TransfersPage.tsx           # Full transfer history
│       │       ├── android/                    # Android-specific screens
│       │       │   ├── AndroidDashboard.tsx
│       │       │   ├── AndroidBrowser.tsx
│       │       │   ├── AndroidOnboarding.tsx
│       │       │   ├── AndroidSettings.tsx
│       │       │   ├── FileDetails.tsx
│       │       │   ├── ShareLinkScreen.tsx
│       │       │   ├── ShareQRDialog.tsx
│       │       │   └── WelcomeScreen.tsx
│       │       ├── web/                        # Remote web console
│       │       │   ├── WebConsole.tsx          # Main console (~1800 lines)
│       │       │   ├── PreviewModal.tsx        # File preview
│       │       │   └── PreviewManager.ts       # Preview capability detection
│       │       ├── figma/
│       │       │   └── ImageWithFallback.tsx
│       │       └── ui/                         # shadcn/ui primitives (48 files)
│       ├── styles/
│       │   ├── index.css              # CSS import aggregator
│       │   ├── tailwind.css           # Tailwind v4 config
│       │   ├── theme.css              # Design tokens + dark theme
│       │   ├── console.css            # Grid layout + responsive breakpoints
│       │   └── fonts.css              # Font imports
│       └── imports/                   # Design reference materials
│
├── android/                           # Capacitor-Generated Android Shell
│   ├── app/
│   ├── build.gradle
│   ├── settings.gradle
│   └── variables.gradle
│
├── ios/                               # Capacitor-Generated iOS Shell
│   └── App/
│
├── github-pages/                      # Static pages for invite flows
│   └── .well-known/
│       └── assetlinks.json            # Android App Links verification
│
└── stitch-designs/                    # Design exploration exports
    ├── images/
    └── code/
```

---

## Module Deep Dive

### 1. Android Node (`app/`)

**Purpose**: Embedded file server running as an Android foreground service.

#### Core Components

| File | Lines | Responsibility |
|------|-------|---------------|
| `ServerService.kt` | ~1200 | Ktor HTTP server with SAF file operations |
| `RelayTunnelClient.kt` | ~370 | WebSocket tunnel client for relay connectivity |
| `WebRTCPeer.kt` | ~590 | WebRTC DataChannel P2P handler |
| `PeerManager.kt` | ~N/A | Multi-peer connection orchestration |
| `TransferManager.kt` | ~160 | Live transfer state via StateFlow |
| `RemoteVaultViewModel.kt` | ~150 | P2P client state & service binding |
| `RemoteNodeClient.kt` | ~350 | Outbound P2P signaling & TAR protocol |
| `P2PClientService.kt` | ~180 | Foreground service for stable P2P downloads |
| `StreamingUploadProxySession.kt` | ~N/A | Chunked upload state machine with sequence tracking |

#### ServerService.kt — Key Endpoints

```kotlin
// Authentication
POST /api/auth/signup    // Create device-local admin account
POST /api/auth/login     // Authenticate, receive JWT token
POST /api/auth/logout    // Clear active session
GET  /api/auth/status    // Check if account exists

// File Operations
GET    /api/files              // List directory (SAF via DocumentFile)
POST   /api/upload_chunk       // 5MB chunked upload
POST   /api/upload_complete    // Finalize chunked upload
POST   /api/folder_manifest    // Pre-create directory tree
POST   /api/folder_complete    // Scan uploaded folder
GET    /api/download           // Stream with Range support (HTTP 206)
GET    /api/download_folder    // Zip and stream folder
GET    /api/download_bulk      // Zip and stream multiple files
POST   /api/mkdir              // Create directory
POST   /api/rename             // Rename file/folder
POST   /api/delete             // Soft delete to .Trash
POST   /api/bulk_action        // Bulk move/delete

// Storage & Status
GET /api/storage      // Storage stats (total/used/free)
GET /api/status       // Node health check
GET /api/peers        // Connected peer list
POST /api/peers/role  // Change peer RBAC role
GET /api/activity     // Activity feed (last 50 events)
GET /api/guest/files  // Public folder access (if enabled)
```

#### Storage Access Framework (SAF)

All file operations use `DocumentFile` for scoped access:

```kotlin
val root = DocumentFile.fromTreeUri(context, rootUri)
val file = root.findFile("example.txt")
val inputStream = context.contentResolver.openInputStream(file.uri)
```

**Benefits**:
- No root access required
- User explicitly grants folder access via system picker
- Works with external SD cards, USB drives, cloud storage providers

#### WebRTC Implementation

**STUN Servers**: `stun.l.google.com:19302`, `stun1.l.google.com:19302`

**DataChannel Protocol**:
- Request ID: UUID (36 chars)
- Chunk size: 64 KB
- Binary protocol header: `[16-byte UUID][1-byte type][4-byte seq][payload]`

**Message Types**:
```kotlin
MSG_TYPE_DATA  = 0  // Chunked file data
MSG_TYPE_START = 1  // Upload stream initiation
MSG_TYPE_END   = 2  // Stream completion
MSG_TYPE_ACK   = 3  // Backend acknowledgment
MSG_TYPE_ERROR = 4  // Error response
```

#### Transfer State Propagation

```
┌─────────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│ TransferRegistry    │────►│ TransferManager  │────►│ UI Layers       │
│ (HTTP uploads)      │     │ (StateFlow)      │     │ - Compose (native)│
│                     │     │                  │     │ - WebView (poll) │
└─────────────────────┘     └──────────────────┘     └─────────────────┘
         │                         │
         ▼                         ▼
  /api/transfer_status      StateFlow<TransferState>
```

---

### 2. Relay Server (`relay/`)

**Purpose**: WebRTC signaling broker + API fallback proxy + static file host.

#### Architecture

```kotlin
RelayServer.kt
├── SignalingRegistry
│   ├── agents: ConcurrentHashMap<shareCode, AgentConnection>
│   ├── browsers: ConcurrentHashMap<shareCode, Map<browserId, WebSocketSession>>
│   └── pendingResponses: ConcurrentHashMap<requestId, CompletableDeferred>
│
├── WebSocket Endpoints
│   ├── /agent/connect?shareCode=XXX    ← Android node connects here
│   └── /signal/{shareCode}             ← Browser connects here
│
├── HTTP Routes
│   ├── /health                         ← Health check
│   ├── /agents                         ← Connected nodes list
│   ├── /node/{code}                    ← Redirect to console
│   ├── /join?code=XXX                  ← Deep link handler
│   └── /api/*                          ← Proxied to Android node
│
└── Static Hosting
    └── /assets/*, /                    ← React SPA
```

#### Relay Envelope Protocol

```kotlin
data class RelayEnvelope(
    val type: String,           // "request" | "response" | "signal" | "stream-request-start"
    val requestId: String?,     // UUID for correlation
    val method: String?,        // HTTP method
    val path: String?,          // API path
    val query: String?,         // URL query string
    val headers: Map<String, String>?,
    val bodyBase64: String?,    // Base64-encoded body (small payloads)
    val contentLength: Long?,   // For streaming uploads
    val status: Int?            // HTTP status (responses only)
)
```

#### Streaming Upload Flow

1. Browser sends `stream-request-start` envelope with `contentLength`
2. Relay forwards to Android node over WebSocket
3. Browser sends binary chunks: `[36-byte header][payload]`
4. Relay forwards chunks without buffering
5. Android extracts, processes, sends `ACK`
6. Relay resolves pending response

#### Timeout Configuration

| Timeout | Value | Purpose |
|---------|-------|-------|
| Ktor WebSocket ping | 20s | Keep-alive |
| Explicit agent ping loop | 25s | Prevent Render idle drops |
| Request timeout | 45s | Standard API calls |
| Streaming upload timeout | 20min | Large file transfers |
| Android OkHttp ping | 15s | Client-side keep-alive |

---

### 3. Web Console (`ui/`)

**Purpose**: React SPA for file management, served by relay (remote) or Android WebView (in-app).

#### Technology Stack

| Layer | Technology |
|-------|-----------|
| Framework | React 18 + TypeScript |
| Build | Vite 6 |
| Routing | React Router v7 (Hash Router) |
| Styling | Tailwind CSS v4 |
| UI Components | Radix UI + shadcn/ui |
| Animations | Framer Motion (`motion/react`) |
| Icons | Lucide React |
| Notifications | Sonner |

#### Platform Detection

```typescript
// App.tsx
const isAndroidWebView = typeof (window as any).Android !== 'undefined';

// Web Console: full-width layout
className={!isAndroidWebView ? 'w-full h-[100dvh]' : 'max-w-md mx-auto'}
```

#### Android Bridge Interface

```typescript
interface AndroidBridge {
  getInitialState(): string;     // JSON: { shareCode, relayUrl, nodeRunning }
  selectFolder(): void;          // Trigger SAF picker
  toggleNode(): void;            // Start/stop server
  shareInvite(): void;           // Native share sheet
  copyToClipboard(text: string, toast: string): void;
  scanDocument(): void;          // Camera document scanner
  showNotification(title: string, message: string): void;
  scanQRCode(): void;            // QR scanner
  shareLink(text: string): void; // Share public link
}
```

#### P2P Transport Layer

**File**: `ui/src/app/hooks/p2pTransport.ts`

Replaces `fetch()` with DataChannel-based transport:

```typescript
const transport = new P2PTransport();
transport.attach(dataChannel);

// Small API calls (JSON response)
const response = await transport.fetch('/api/files?path=Documents');
const json = await response.json();

// Large file upload (binary streaming)
await transport.upload('/api/upload_chunk', query, file, headers);

// Arbitrary stream upload
await transport.uploadStream(path, query, readableStream, options);
```

**Memory Safety**:
- Downloads: Chunks accumulated into `Uint8Array[]`, assembled on `res-end`
- Uploads: Files read in 64 KB slices via `FileReader` or `Blob.stream()`
- Backpressure: Pauses send at 1 MB buffered, resumes at 512 KB

#### Responsive Layout

```css
/* console.css */
.web-console {
  display: grid;
  /* Desktop: Sidebar + File Panel + Preview */
  grid-template-columns: clamp(200px, 20vw, 260px) 1fr clamp(240px, 22vw, 320px);
}

@media (max-width: 1024px) {
  /* Tablet: Sidebar + File Panel */
  grid-template-columns: clamp(200px, 22vw, 240px) 1fr;
}

@media (max-width: 600px) {
  /* Mobile: File Panel only */
  grid-template-columns: 1fr;
}
```

---

## Data Flows

### 1. WebRTC P2P Connection (Happy Path)

```
Browser                          Relay                        Android Node
   │                               │                               │
   ├──WS /signal/{code}──────────►│                               │
   │                               │◄──WS /agent/connect──────────┤
   │                               │                               │
   │──SDP Offer──────────────────►│──Forward────────────────────►│
   │◄──SDP Answer─────────────────│◄──Forward────────────────────│
   │──ICE Candidates─────────────►│──Forward────────────────────►│
   │◄──ICE Candidates─────────────│◄──Forward────────────────────│
   │                               │                               │
   │◄════════════════════════════════════════════════════════════►│
   │              WebRTC DataChannel (DTLS-encrypted)             │
   │         File bytes flow directly, zero relay involvement     │
```

### 2. Relay Fallback (NAT/Firewall)

```
Browser                          Relay                        Android Node
   │                               │                               │
   │──GET /api/files─────────────►│                               │
   │   X-Node-Id: ABC123          │                               │
   │                               │──RelayEnvelope─────────────►│
   │                               │   type: "request"            │
   │                               │   path: "/api/files"         │
   │                               │   requestId: "uuid"          │
   │                               │                               │
   │                               │◄──RelayEnvelope──────────────│
   │                               │   type: "response"           │
   │                               │   status: 200                │
   │                               │   bodyBase64: "ey..."        │
   │◄──JSON Response──────────────│                               │
```

### 3. Chunked File Upload (P2P)

```
Browser                                    Android Node
   │                                            │
   ├──START packet (metadata)─────────────────►│
   │   [UUID][TYPE=1][SEQ=0][JSON meta]        │
   │                                            │
   ├──DATA packets (64 KB chunks)─────────────►│
   │   [UUID][TYPE=0][SEQ=n][payload]          │
   │   ... (repeat for all chunks)              │
   │                                            │
   ├──END packet──────────────────────────────►│
   │   [UUID][TYPE=2][SEQ=n]                   │
   │                                            │
   │◄──ACK packet───────────────────────────────│
   │   [UUID][TYPE=3][SEQ=0][{"success":true}] │
   │                                            │
   ▼                                            ▼
Transfer complete, UI updates progress
```

### 4. Range Request Download

```
Browser                                    Android Node
   │                                            │
   ├──GET /api/download?path=video.mp4        │
   │   Range: bytes=0-1048575                  │
   │                                            │
   │◄──HTTP 206 Partial Content─────────────────│
   │   Content-Range: bytes 0-1048575/52428800 │
   │   Accept-Ranges: bytes                    │
   │   Content-Type: video/mp4                 │
   │   [1 MB chunk streamed]                   │
   │                                            │
   ├──GET (next range)────────────────────────►│
   │   Range: bytes=1048576-2097151            │
   │                                            │
   │◄──HTTP 206 (next chunk)────────────────────│
```

---

## Security Architecture

### Authentication Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    SECURITY MODEL                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Layer 1: Android SAF Sandbox                               │
│  └─> User grants explicit folder access via system picker  │
│                                                              │
│  Layer 2: Device-Local JWT Auth                             │
│  └─> SHA-256 password hash + UUID token in SharedPreferences│
│                                                              │
│  Layer 3: Share Code Isolation                              │
│  └─> 10-char uppercase hex, relay routes by exact match    │
│                                                              │
│  Layer 4: Role-Based Access Control (RBAC)                  │
│  └─> VIEWER < CONTRIBUTOR < MANAGER < ADMIN                │
│                                                              │
│  Layer 5: Transport Security                                │
│  ├─> Relay ↔ Browser: HTTPS (Render TLS)                   │
│  ├─> Relay ↔ Node: WSS (WebSocket over TLS)                │
│  └─> Browser ↔ Node: DTLS (WebRTC encrypted)               │
│                                                              │
│  Layer 6: Guest Access (Optional)                           │
│  └─> Public folder with read-only access, no auth required │
└─────────────────────────────────────────────────────────────┘
```

### RBAC Role Hierarchy

| Role | Permissions |
|------|-------------|
| `VIEWER` | List files, download, preview |
| `CONTRIBUTOR` | Viewer + upload, create folders |
| `MANAGER` | Contributor + delete, rename, move |
| `ADMIN` | All permissions + role management |

---

## Build & Deployment

### Android App Build

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install on connected device
./gradlew :app:installDebug

# Build requires:
# - JDK 17+
# - Android SDK 34
# - local.properties with RELAY_BASE_URL, APP_LINK_HOST
```

### Web Console Build

```bash
cd ui
npm install
npm run build  # → ui/dist/

# Sync to Android assets
npm run build:ui  # Copies dist/ → app/src/main/assets/web/
```

### Relay Deployment (Render)

```yaml
# render.yaml
services:
  - type: web
    name: easy-storage-relay
    runtime: docker
    dockerContext: ./relay
    healthCheckPath: /health
    region: singapore
    plan: free
```

```bash
# Local testing
./gradlew :relay:run
curl http://127.0.0.1:8787/health
```

---

## Key Configuration Values

| Constant | Location | Value | Purpose |
|----------|----------|-------|---------|
| `DEFAULT_PORT` | `ServerUtils.kt` | `8080` | Android Ktor server bind |
| `RELAY_PORT` | `RelayServer.kt` | `8787` | Relay bind (local) |
| `CHUNK_SIZE` | `p2pTransport.ts` / `WebRTCPeer.kt` | `64 KB` | DataChannel packet size |
| Upload chunk size | `WebConsole.tsx` / `ServerService.kt` | `5 MB` | Application upload slicing |
| WebSocket ping | `RelayServer.kt` | `20s` | Ktor keep-alive |
| Agent ping loop | `RelayServer.kt` | `25s` | Prevent Render idle drops |
| OkHttp ping | `RelayTunnelClient.kt` | `15s` | Client-side keep-alive |
| Request timeout | `RelayServer.kt` | `45s` | Standard API relay |
| Streaming timeout | `RelayServer.kt` | `20min` | Large file transfers |

---

## Known Limitations (Beta)

| Issue | Impact | Workaround / Status |
|-------|--------|---------------------|
| Relay buffers bodies in memory | 50 MB cap on free tier | P2P path has no limit |
| Filenames sanitized | Spaces → underscores | Being addressed |
| Render cold starts | 10-30s delay after idle | Client retry logic |
| WebRTC NAT traversal fails | ~15% of networks | Relay fallback handles it |
| No TURN server | Symmetric NAT issues | STUN-only for now |
| Single admin per node | Multi-user requires Phase 2 | Supabase auth planned |

---

## Performance Characteristics

| Metric | Value | Notes |
|--------|-------|-------|
| Web Console bundle | 672 KB JS + 130 KB CSS | ~232 KB gzip'd |
| Relay RAM (10-12 sessions) | 50-80 MB | When P2P succeeds |
| Directory listing (<500 files) | <200 ms | Relay adds latency |
| P2P upload throughput | 5-15 MB/s | Network-dependent |
| LAN direct HTTP | ~50 MB/s | Local network only |
| WebRTC connection time | 1-3 s | After signaling |

---

## Roadmap

| Phase | Target | Features |
|-------|--------|----------|
| Phase 1 | COMPLETED | Core stability, relay hardening, upload correctness |
| Phase 2 | COMPLETED | Native Android-to-Android P2P signaling & browsing |
| Phase 3 | Q3 2026 | Play Store, file preview, auto photo backup |
| Phase 4 | Q4 2026 | Scale to 1000 Pro users, team tier, enterprise APIs |
