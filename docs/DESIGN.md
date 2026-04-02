# Easy Storage Cloud — System Design Document

> **Version:** 2.0 · **Last Updated:** April 2026  
> **Author:** Pratham Pandey  

---

## 1. Executive Summary

Easy Storage Cloud transforms any Android device into a fully-functional personal cloud storage node accessible from anywhere in the world. Unlike traditional cloud services that rely on centralized servers, Easy Storage operates on a **decentralized peer-to-peer architecture** — your files never leave your phone unless you choose to share them.

The system consists of three tightly integrated modules:
- **Android Node** — An embedded Ktor HTTP server running as a foreground service
- **Relay Server** — A lightweight Ktor signaling gateway deployed on Render
- **Web Console** — A React SPA served by the relay, providing full file management via WebRTC

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        EASY STORAGE CLOUD ARCHITECTURE                     │
│                                                                            │
│   ┌──────────────┐    WebSocket     ┌──────────────┐    WebRTC    ┌──────┐ │
│   │  Android App │◄═══════════════►│ Relay Server  │◄═══════════►│ Web  │ │
│   │  (Ktor Node) │  (Signaling +   │  (Render.com) │  (P2P Data  │ UI   │ │
│   │              │   API Fallback)  │              │   Channel)   │      │ │
│   └──────┬───────┘                 └──────────────┘              └──────┘ │
│          │                                                                 │
│          │ Storage Access Framework (SAF)                                  │
│          ▼                                                                 │
│   ┌──────────────┐                                                        │
│   │   External   │                                                        │
│   │   Storage    │                                                        │
│   └──────────────┘                                                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Module Architecture

### 2.1 Android Node (`/app`)

The Android module is the heart of the system — a full HTTP file server embedded within a native Android application.

#### Core Components

| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | Main activity hosting the Capacitor WebView, JavaScript bridge (`@JavascriptInterface`), foreground service management, and deep link handling |
| `ServerService.kt` | Foreground service running the embedded Ktor server (port 8080). Handles all REST API endpoints: file listing, upload (chunked + streaming), download, delete, rename, folder creation, storage stats, and local auth |
| `RelayTunnelClient.kt` | Persistent WebSocket client maintaining the tunnel to the relay server. Processes `RelayEnvelope` request/response pairs for remote API access |
| `WebRTCPeer.kt` | WebRTC data channel handler for browser ↔ phone P2P communication. Manages SDP offer/answer exchange, ICE candidate handling, and data channel message routing |
| `ServerUtils.kt` | Utility functions for MIME type detection, file size formatting, and path sanitization |

#### API Endpoints (Local Server — Port 8080)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/files` | List directory contents with metadata |
| `POST` | `/api/upload` | Chunked file upload (supports resumable uploads) |
| `GET` | `/api/download/{path}` | Stream file download with range support |
| `DELETE` | `/api/delete` | Delete file or directory |
| `POST` | `/api/rename` | Rename file or directory |
| `POST` | `/api/folder` | Create new directory |
| `GET` | `/api/storage` | Drive capacity stats (total/used/free bytes) |
| `GET` | `/api/status` | Node health check |
| `POST` | `/api/auth/signup` | Create admin identity |
| `POST` | `/api/auth/login` | Authenticate and receive JWT token |
| `GET` | `/api/auth/status` | Check if an account exists |

#### Storage Access

Uses Android's **Storage Access Framework (SAF)** via `DocumentFile` for secure, scoped access to external storage. The user selects a root folder through the system file picker, and the app operates within that sandbox.

#### Security Model

- **JWT Authentication**: Local auth with email/password stored on-device
- **Bearer Token**: All API requests require `Authorization: Bearer <token>`
- **Share Code**: 10-character uppercase hex code derived from device identity, used to identify nodes on the relay

---

### 2.2 Relay Server (`/relay`)

A lightweight Ktor/Netty server deployed on Render (Singapore region, free tier) that serves as:
1. **WebRTC Signaling Gateway** — Brokers SDP/ICE candidate exchange between browsers and Android nodes
2. **API Fallback Proxy** — When WebRTC fails, forwards REST requests to the phone over WebSocket
3. **Static File Host** — Serves the React SPA build

#### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         RELAY SERVER                                │
│                                                                     │
│  ┌─────────────┐    ┌──────────────────┐    ┌──────────────────┐   │
│  │   Health     │    │ Static Resources │    │  Landing Page    │   │
│  │  /health     │    │  /assets/*       │    │  /nodes          │   │
│  └─────────────┘    │  /*  (SPA)       │    └──────────────────┘   │
│                     └──────────────────┘                            │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │               WebSocket Layer                                │   │
│  │  /agent/connect?shareCode=XXX   ← Android node connects     │   │
│  │  /signal/{shareCode}            ← Browser connects           │   │
│  └─────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │               API Proxy Layer                                │   │
│  │  /api/{...}                     ← Forwarded to Android node │   │
│  │  X-Node-Id or ?nodeId=XXX      ← Node identification       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │               Navigation                                     │   │
│  │  /node/{code}  → redirect /#/console/{code}                 │   │
│  │  /join?code=X  → redirect /#/console/{code}                 │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

#### Relay Envelope Protocol

API requests are serialized into `RelayEnvelope` JSON objects for transport over WebSocket:

```kotlin
data class RelayEnvelope(
    val type: String,           // "request" | "response"
    val requestId: String?,     // UUID for request-response correlation
    val method: String?,        // HTTP method
    val path: String?,          // API path
    val query: String?,         // URL-encoded query string
    val headers: Map<String, String>?,
    val bodyBase64: String?,    // Base64-encoded request/response body
    val status: Int?,           // HTTP status code (responses only)
    val error: String?          // Error message (responses only)
)
```

#### Signaling Registry

The `SignalingRegistry` maintains concurrent maps of:
- **Agents** (`ConcurrentHashMap<shareCode, AgentConnection>`) — Connected Android nodes
- **Browsers** (`ConcurrentHashMap<shareCode, Map<browserId, WebSocketSession>>`) — Connected web clients
- **Pending Responses** (`ConcurrentHashMap<requestId, CompletableDeferred>`) — In-flight API relay requests

Each agent connection is protected by a `Mutex` to prevent concurrent WebSocket frame writes.

---

### 2.3 Web Console (`/ui`)

A React 18 SPA built with Vite, designed to serve as the primary file management interface when accessing a node remotely.

#### Technology Stack

| Layer | Technology |
|-------|-----------|
| Framework | React 18 + TypeScript |
| Build Tool | Vite 6 |
| Routing | React Router v7 (Hash Router) |
| Styling | Tailwind CSS v4 + Custom CSS |
| UI Components | Radix UI + shadcn/ui (48 components) |
| Animations | Framer Motion (`motion/react`) |
| Icons | Lucide React |
| Notifications | Sonner (toast) |

#### Component Hierarchy

```
App.tsx (Root)
├── LoadingScreen.tsx          — Animated boot screen
├── WelcomeScreen.tsx          — First-run tutorial prompt (Android only)
├── AndroidOnboarding.tsx      — Onboarding carousel (Android only)
├── Auth Screen                — Local JWT login/signup (Android only)
│
├── Root.tsx (Router Outlet)
│   ├── AndroidDashboard.tsx   — Node control panel (Android WebView)
│   ├── AndroidBrowser.tsx     — Mobile file browser (Android WebView)
│   ├── AndroidSettings.tsx    — Settings panel (Android WebView)
│   ├── ShareLinkScreen.tsx    — QR code sharing (Android WebView)
│   │
│   └── WebConsole.tsx         — Full desktop/tablet/mobile web console
│       ├── Topbar             — Logo, search, node status, settings, user menu
│       ├── Sidebar            — Upload/folder actions, Drive/Recent/Shared/Trash tabs, storage meter
│       ├── File Panel          — Breadcrumb, list/grid view toggle, file table with columns
│       └── Preview Panel       — File preview with metadata and download button
```

#### Responsive Layout System

The web console uses a CSS Grid layout with three responsive breakpoints:

| Viewport | Columns | Visible Panels |
|----------|---------|----------------|
| **Desktop** (>1024px) | `clamp(200px, 20vw, 260px) 1fr clamp(240px, 22vw, 320px)` | Sidebar + File Panel + Preview |
| **Tablet** (769–1024px) | `clamp(200px, 22vw, 240px) 1fr` | Sidebar + File Panel |
| **Small Tablet** (601–768px) | `200px 1fr` | Sidebar + File Panel |
| **Mobile** (≤600px) | `1fr` | File Panel only (hamburger menu for sidebar drawer) |

#### Platform Detection

The wrapper div in `App.tsx` conditionally applies layout constraints:
- **Web Console** (`!window.Android`): Full-width `w-full h-[100dvh] overflow-hidden`
- **Android WebView** (`window.Android` present): Constrained `max-w-md mx-auto` for mobile viewport

#### Android Bridge (`bridge.ts`)

The `window.Android` JavaScript interface exposes native capabilities to the WebView:

```typescript
interface AndroidBridge {
  getInitialState(): string;     // JSON serialized AppState
  selectFolder(): void;          // SAF folder picker
  toggleNode(): void;            // Start/stop server
  shareInvite(): void;           // Native share sheet
  copyToClipboard(text, toast): void;
  scanDocument(): void;          // Camera document scanner
  showNotification(title, msg): void;
  scanQRCode(): void;            // QR code scanner
  shareLink(text): void;         // Share link via native UI
}
```

---

## 3. Data Flow

### 3.1 Remote File Access (WebRTC — Primary)

```
Browser                    Relay                    Android Node
  │                          │                          │
  ├──WS /signal/{code}──────►│                          │
  │                          │◄──WS /agent/connect──────┤
  │                          │                          │
  │──SDP Offer──────────────►│──Forward──────────────►│
  │◄──SDP Answer─────────────│◄──Forward──────────────│
  │──ICE Candidates─────────►│──Forward──────────────►│
  │◄──ICE Candidates─────────│◄──Forward──────────────│
  │                          │                          │
  │◄═══════ WebRTC Data Channel (P2P) ═════════════►│
  │  (File data flows directly, zero relay bytes)      │
```

### 3.2 Remote File Access (Relay Fallback)

When WebRTC fails (NAT issues, firewall restrictions):

```
Browser                    Relay                    Android Node
  │                          │                          │
  │── GET /api/files ───────►│                          │
  │   X-Node-Id: ABC123      │                          │
  │                          │── RelayEnvelope ────────►│
  │                          │   {type: "request",      │
  │                          │    path: "/api/files",   │
  │                          │    requestId: "uuid"}    │
  │                          │                          │
  │                          │◄── RelayEnvelope ────────│
  │                          │   {type: "response",     │
  │                          │    status: 200,          │
  │                          │    bodyBase64: "..."}    │
  │◄── JSON Response ────────│                          │
```

### 3.3 Chunked File Upload

```
Browser                              Android Node
  │                                      │
  │── POST /api/upload ─────────────────►│
  │   Content-Type: multipart/form-data  │
  │   X-Chunk-Index: 0                   │
  │   X-Total-Chunks: 5                  │
  │   X-File-Name: video.mp4            │
  │                                      │
  │◄── {status: "chunk_received"} ───────│
  │    ... (repeat for chunks 1-3)       │
  │── POST /api/upload (chunk 4) ───────►│
  │◄── {status: "complete",             │
  │     path: "/video.mp4"} ─────────────│
```

---

## 4. Deployment Architecture

### 4.1 Build Pipeline (Docker Multi-Stage)

```dockerfile
# Stage 1: Build React Frontend
FROM node:20-alpine AS frontend-build
WORKDIR /ui
COPY ui/package.json ui/package-lock.json* ./
RUN npm install
COPY ui/ ./
RUN npm run build                         # → /ui/dist/

# Stage 2: Build Ktor Backend
FROM gradle:8.7.0-jdk17 AS backend-build
WORKDIR /workspace
COPY relay/build.gradle.kts relay/settings.gradle.kts ./
COPY relay/src ./src
COPY --from=frontend-build /ui/dist ./src/main/resources/static
RUN gradle --no-daemon installDist        # Bundles React into Ktor classpath

# Stage 3: Runtime
FROM eclipse-temurin:17-jre
COPY --from=backend-build /workspace/build/install/easy-storage-relay/ ./
CMD ["./bin/easy-storage-relay"]           # Port 10000
```

### 4.2 Render Configuration

```yaml
services:
  - type: web
    name: easy-storage-relay
    runtime: docker
    dockerContext: .
    dockerfilePath: ./relay/Dockerfile
    healthCheckPath: /health
    region: singapore
    plan: free
    autoDeployTrigger: commit
```

- **Auto-deploy**: Every push to `main` triggers a fresh Docker build
- **Health check**: `/health` returns `relay_online` (plain text 200)
- **Unified artifact**: Single Docker image contains both the Ktor server and the React SPA

---

## 5. Design System

### 5.1 Color Palette

| Token | Hex | Usage |
|-------|-----|-------|
| `--background` | `#0B1220` | Primary background |
| `--foreground` | `#E5E7EB` | Primary text |
| `--card` | `#111827` | Card/panel backgrounds |
| `--primary` | `#2563EB` | Primary actions, links |
| `--accent` | `#22C55E` | Success states, online indicators |
| `--highlight` | `#A855F7` | Accent highlights, gradients |
| `--destructive` | `#EF4444` | Delete, error states |
| `--muted` | `#374151` | Borders, dividers |
| `--muted-foreground` | `#9CA3AF` | Secondary text |

### 5.2 Typography

- **Primary**: Inter, -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif
- **Monospace**: JetBrains Mono, Courier New, monospace
- **Base size**: 16px (`--font-size`)

### 5.3 Visual Style

- **Theme**: "Terminal-Chic" — dark, cyberpunk-inspired aesthetic with blue/purple gradients
- **Corners**: 0.75rem base radius (12px)
- **Effects**: Glassmorphism (`backdrop-blur-xl`), glow effects (`shadow-blue-500/20`)
- **Appearance**: Dark mode default, with invertible light mode via CSS `filter: invert(1) hue-rotate(180deg)`

---

## 6. Security Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   SECURITY LAYERS                            │
│                                                              │
│  Layer 1: Android SAF Sandbox                               │
│  ├── User explicitly grants access to a specific folder     │
│  └── App cannot access files outside the granted tree       │
│                                                              │
│  Layer 2: Local JWT Authentication                          │
│  ├── Admin account created on first run (email + password)  │
│  ├── JWT token issued on login, stored in localStorage      │
│  └── All API endpoints require valid Bearer token           │
│                                                              │
│  Layer 3: Share Code Isolation                              │
│  ├── Each node identified by unique 10-char hex code        │
│  ├── Relay only forwards requests to matching shareCode     │
│  └── No cross-node access possible                          │
│                                                              │
│  Layer 4: Transport Security                                │
│  ├── Relay ↔ Browser: HTTPS (Render TLS)                   │
│  ├── Relay ↔ Node: WSS (WebSocket over TLS)                │
│  └── Browser ↔ Node: DTLS (WebRTC encrypted channel)       │
│                                                              │
│  Layer 5: Upload Size Limits                                │
│  ├── Relay proxy: 50MB max (free tier memory constraint)    │
│  └── Direct LAN: Unlimited (device storage only limit)     │
└─────────────────────────────────────────────────────────────┘
```

---

## 7. Key Design Decisions

### 7.1 Why WebRTC + Relay Fallback?

| Approach | Pros | Cons |
|----------|------|------|
| **Pure Relay** | Simple, always works | All data passes through server (bandwidth cost, 50MB limit) |
| **Pure WebRTC** | Zero server bandwidth, unlimited size | NAT traversal fails ~15% of the time |
| **Hybrid (chosen)** | Best of both worlds | More complex connection logic |

The hybrid approach attempts WebRTC first. If the data channel cannot be established within a timeout, the web console automatically falls back to relay-proxied API calls.

### 7.2 Why Embedded Ktor on Android?

- **No external dependencies**: The phone IS the server
- **Foreground Service**: Runs reliably in background with notification
- **SAF integration**: Secure access to external storage without root
- **Local-first**: Works on LAN without internet, relay only needed for remote access

### 7.3 Why Single Codebase for Android + Web UI?

The React app serves dual purposes:
- **Android WebView**: Rendered inside Capacitor/WebView with constrained `max-w-md` layout, accessing native APIs via `window.Android` bridge
- **Desktop Browser**: Full-width responsive grid layout, connecting via WebRTC/relay

This eliminates the need to maintain separate codebases while allowing platform-specific behavior through the `window.Android` bridge detection.

### 7.4 Why Hash Router?

The app uses `createHashRouter` instead of `createBrowserRouter` because:
- Static file hosting (Ktor `staticResources`) doesn't natively support HTML5 pushState
- Hash-based routes (`/#/console/CODE`) work with any static file server
- The SPA catch-all route (`get("{...}")`) provides a fallback, but hash routing is more reliable

---

## 8. Project Structure

```
AndroidCloudStorageApp/
├── app/                                    # Android native module
│   └── src/main/java/com/pratham/cloudstorage/
│       ├── MainActivity.kt                 # WebView host + JS bridge
│       ├── ServerService.kt                # HTTP server (foreground service)
│       ├── RelayTunnelClient.kt            # WebSocket tunnel to relay
│       ├── WebRTCPeer.kt                   # WebRTC data channel
│       ├── ServerUtils.kt                  # File utilities
│       └── cloud/                          # Cloud-specific helpers
│
├── relay/                                  # Relay server module
│   ├── Dockerfile                          # Multi-stage build (React + Ktor)
│   ├── build.gradle.kts                    # Ktor + Netty + Gson dependencies
│   └── src/main/kotlin/.../RelayServer.kt  # Complete relay implementation
│
├── ui/                                     # React frontend
│   ├── index.html                          # SPA entry point
│   ├── vite.config.ts                      # Vite build configuration
│   ├── dist/                               # Production build output
│   └── src/
│       ├── app/
│       │   ├── App.tsx                     # Root component with auth flow
│       │   ├── routes.ts                   # Hash router configuration
│       │   ├── bridge.ts                   # Android JS interface types
│       │   └── components/
│       │       ├── android/                # Android-specific screens (8 files)
│       │       ├── web/
│       │       │   └── WebConsole.tsx       # Full web console (79KB, ~1800 lines)
│       │       ├── ui/                     # shadcn/ui components (48 files)
│       │       ├── Root.tsx                # Router outlet wrapper
│       │       └── LoadingScreen.tsx       # Animated boot sequence
│       └── styles/
│           ├── index.css                   # Import aggregator
│           ├── tailwind.css                # Tailwind v4 configuration
│           ├── theme.css                   # Design tokens + base styles
│           ├── console.css                 # Web console layout (grid + responsive)
│           └── fonts.css                   # Font imports
│
├── stitch-designs/                         # Stitch design exports
│   ├── images/                             # Screen design PNGs (8 screens)
│   └── code/                               # Generated HTML prototypes
│
├── render.yaml                             # Render deployment configuration
├── capacitor.config.json                   # Capacitor configuration
└── README.md                               # Project overview
```

---

## 9. Performance Characteristics

| Metric | Value | Notes |
|--------|-------|-------|
| **Web Console bundle** | 672 KB JS + 130 KB CSS | Tree-shaken, gzip'd to ~232 KB total |
| **Relay cold start** | ~10-15s | Render free tier spin-up |
| **WebRTC connection** | 1-3s | After signaling exchange |
| **File listing latency** | <100ms (LAN), 200-500ms (Relay) | Depends on file count |
| **Upload throughput** | ~50 MB/s (LAN), 5-15 MB/s (WebRTC) | Device and network dependent |
| **Concurrent nodes** | Unlimited (relay) | Limited by Render memory (512MB free) |

---

## 10. Known Limitations

1. **Relay Upload Cap**: 50MB per file via relay proxy (WebSocket frame size constraint on free tier)
2. **Render Spin-down**: Free tier sleeps after 15 min inactivity; first request has 10-15s cold start
3. **WebRTC NAT Issues**: Symmetric NAT configurations may prevent P2P connections (~15% of networks)
4. **Single Admin**: Only one admin account per node instance
5. **No End-to-End Encryption**: Files are encrypted in transit (TLS/DTLS) but not at rest on the device
6. **Android 10+ Required**: SAF and foreground service APIs require API 29+

---

## 11. Future Roadmap

- [ ] Multi-user access with role-based permissions
- [ ] File version history and conflict resolution  
- [ ] End-to-end encryption for files at rest
- [ ] iOS companion app
- [ ] TURN server integration for 100% WebRTC connectivity
- [ ] Selective folder sync across multiple nodes
- [ ] File search with full-text indexing
