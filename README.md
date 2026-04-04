# Easy Storage Cloud 📱☁️

A high-performance Android storage node that transforms your phone's external storage into a personal cloud accessible from anywhere in the world.

## 🚀 Overview
Easy Storage Cloud runs a lightweight Ktor server on your Android device, allowing you to browse, download, and upload files through a modern web console. It uses a custom **Relay Tunnel Architecture** to make your files accessible over the internet without manual port forwarding.

## 🏗️ Architecture
The project consists of two main components:
1.  **Android App (`/app`)**: 
    - **Native Interface**: A high-fidelity Jetpack Compose dashboard with real-time transfer tracking.
    - **Embedded WebView**: Uses **androidx.webkit.WebViewAssetLoader** to securely serve the React-based console from `https://appassets.androidplatform.net`. This prevents CORS issues and enables modern ES Modules support.
    - **Ktor Server**: Local high-performance web server that handles P2P file transfers and storage management.
2.  **Web Console (`/ui`)**: 
    - A Next-generation React + Tailwind v4 interface that runs both natively on the phone and remotely via the relay.

## ✨ Key Features
- **P2P Direct Streaming**: High-speed chunked uploading and downloading that bypasses the cloud entirely when possible.
- **Modern Security**: All internal communication is signed and restricted via the storage access context.
- **Relay Tunneling**: Access your node from anywhere with zero configuration.

## 🛠️ Setup & Development

### Modern WebView Architecture
The app now uses a secure virtual domain for internal assets. 
- **Internal Domain**: `https://appassets.androidplatform.net`
- **Asset Path**: `assets/web/` maps directly to `src/main/assets/web/`

### Build & Deploy
1. **Build the UI**:
   ```bash
   cd ui && npm run build
   # Assets are automatically synced to app/src/main/assets/web/
   ```
2. **Launch on Device**:
   ```bash
   ./gradlew installDebug
   ```

## ⚠️ Important Technical Notes
- **WebView Performance**: The app requires a modern system WebView (Chromium 100+ recommended).
- **Relay Upload Limit**: Remote uploads over the proxy relay are currently capped at **50MB** due to cloud provider memory limits. Direct LAN transfers have no limit.

## 📄 License
Designed and developed for high-speed mobile cloud sharing.
