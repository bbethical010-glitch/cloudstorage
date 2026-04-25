# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

### Android App
```bash
# Build debug APK
./gradlew :app:assembleDebug

# Install debug APK
./gradlew :app:installDebug

# Run embedded relay server locally (for testing)
./gradlew :relay:run
```

### Web Console (UI)
```bash
cd ui
npm install
npm run dev          # Development server
npm run build        # Production build → ui/dist/
```

### Sync Web Console to Android Assets
```bash
npm run build:ui     # Builds UI and copies to app/src/main/assets/web/
```

### Relay Server
```bash
# Run locally (listens on port 8787)
./gradlew :relay:run

# Health check
curl http://127.0.0.1:8787/health
```

### Configuration
Create `local.properties` at project root:
```properties
RELAY_BASE_URL=https://your-relay-url.onrender.com
APP_LINK_HOST=invite.easystoragecloud.app
```

## Architecture Overview

Three-layer decentralized storage system:

```
┌──────────────┐    WebSocket     ┌──────────────┐    WebRTC    ┌──────────┐
│ Android Node │◄═══════════════►│ Relay Server │◄═══════════►│ Browser  │
│ (Ktor 8080)  │  (Signaling +   │  (Render)    │  (P2P Data │  (React) │
│              │   API Fallback)  │              │   Channel) │          │
└──────┬───────┘                 └──────────────┘            └──────────┘
       │
       │ Storage Access Framework (SAF)
       ▼
┌──────────────┐
│ External SSD │
└──────────────┘
```

### Layer 1: Android Node (`app/`)
- **ServerService.kt**: Embedded Ktor HTTP server (port 8080) running as foreground service
- **RelayTunnelClient.kt**: Persistent WebSocket tunnel to relay for signaling and API fallback
- **WebRTCPeer.kt**: WebRTC data channel handler for direct P2P file transfer
- All storage access via Android SAF (`DocumentFile`) - no root required

### Layer 2: Relay Server (`relay/`)
- Ktor/Netty server deployed on Render (Singapore, free tier)
- **Roles**: WebRTC signaling broker, HTTP fallback proxy, static file host
- **Key endpoints**: `/health`, `/agents`, `/node/<share_code>`, `/api/*` (proxied)
- **RelayEnvelope protocol**: JSON envelopes with Base64 bodies for WebSocket transport

### Layer 3: Web Console (`ui/`)
- React 18 + Vite + TypeScript SPA with Radix UI/shadcn components
- **useWebRTC hook**: Manages signaling, SDP offer/answer, ICE candidates, DataChannel lifecycle
- **P2PTransport**: 64 KB packet slicing for stable DataChannel memory
- **Platform detection**: `window.Android` bridge for in-app WebView vs full web console

## Key Configuration Values

| Constant | Location | Value | Purpose |
|----------|----------|-------|---------|
| Android HTTP port | `ServerUtils.kt` | `8080` | Local Ktor server bind |
| Relay port | `RelayServer.kt` | `8787` (local) | Relay bind port |
| Upload chunk size | `WebConsole.tsx` / `ServerService.kt` | `5 MB` | Application-level slicing |
| P2P packet size | `p2pTransport.ts` / `WebRTCPeer.kt` | `64 KB` | DataChannel packet size |
| WebSocket ping | `RelayServer.kt` | `25s` explicit + `20s` Ktor | Prevent Render idle drops |

## File Transfer Pipeline

**Upload (Chunked)**: Browser → `5 MB` chunks → `/api/upload_chunk` → Android SAF write → `/api/upload_complete`

**Download (Streaming)**: Browser → `/api/download?path=...` with `Range` header → Android streams with `HTTP 206 Partial Content`

**P2P Path**: File bytes bypass relay entirely via WebRTC DataChannel once negotiated

**Relay Fallback**: Base64-encoded envelopes over WebSocket when P2P fails (NAT/firewall)

## Transfer State Tracking

- **TransferRegistry.kt**: Kotlin singleton for chunked HTTP upload state, exposed via `/api/transfer_status`
- **TransferManager.kt**: Aggregates WebRTC upload/download progress, publishes `StateFlow` for Compose UI
- **TransferIndicatorBar.tsx**: Browser polls `/api/transfer_status` for remote progress display

## Known Limitations (Beta)

- Relay fallback buffers bodies in memory (not yet streaming) - 50MB cap on free tier
- Filenames with spaces sanitized to underscores
- Render cold starts: 10-30s delay after idle sleep
- WebRTC fails behind strict NAT/corporate firewalls (~15% of networks)

## Project Structure

```
AndroidCloudStorageApp/
├── app/                    # Android module (Kotlin, Ktor, SAF, WebRTC, Compose)
├── relay/                  # Relay server (Kotlin, Ktor, WebSocket signaling)
├── ui/                     # React + Vite web console
├── android/                # Capacitor-generated Android wrapper
├── ios/                    # Capacitor-generated iOS wrapper
├── docs/                   # Architecture, deployment, runbook docs
├── render.yaml             # Render deployment config
└── capacitor.config.json   # Capacitor configuration
```
