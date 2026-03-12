# Easy Storage Cloud 📱☁️

A high-performance Android storage node that transforms your phone's external storage into a personal cloud accessible from anywhere in the world.

## 🚀 Overview
Easy Storage Cloud runs a lightweight Ktor server on your Android device, allowing you to browse, download, and upload files through a modern web console. It uses a custom **Relay Tunnel Architecture** to make your files accessible over the internet without manual port forwarding.

## 🏗️ Architecture
The project consists of two main components:
1.  **Android App (`/app`)**: 
    - **Server**: Embedded Ktor server running in a Foreground Service.
    - **Storage**: Uses Android Storage Access Framework (SAF) for secure file access.
    - **Client**: Connects to the Relay Server via a persistent WebSocket tunnel for remote access.
2.  **Relay Server (`/relay`)**: 
    - A public-facing proxy (hosted on Render) that tunnels HTTP traffic to the phone node via WebSockets.
    - Handles deep-link redirection for easy joining via Share Codes.

## ✨ Key Features
- **Remote Console**: Access your phone's drive via `https://easy-storage-relay.onrender.com/node/YOUR_CODE`.
- **Hybrid Access**: 
  - **Public Relay**: Access from anywhere over the internet.
  - **Direct LAN**: High-speed, unlimited access when on the same Wi-Fi.
- **Streaming Uploads**: Efficient file handling that prevents memory errors even for large files.
- **Share Links**: Direct URL generation for files to share with others.
- **Deep Linking**: Join nodes instantly using `easystoragecloud://join?code=...` or HTTPS links.

## 🛠️ Setup & Development

### Local Configuration
Values are managed in `local.properties`:
```properties
RELAY_BASE_URL=https://easy-storage-relay.onrender.com
APP_LINK_HOST=easy-storage-relay.onrender.com
```

### Running the App
1. Connect your Android device.
2. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### Debugging Commands
Start the server node manually via ADB:
```bash
adb shell am start -n com.pratham.cloudstorage/.DebugStartActivity -a com.pratham.cloudstorage.debug.START_NODE
```

## ⚠️ Important Technical Notes
- **Relay Upload Limit**: Due to memory constraints on the free-tier relay server, uploads over the relay are capped at **50MB**. For larger files, use the **Direct Private LAN** link provided on the dashboard.
- **WebSocket Tunneling**: Requests are encapsulated into `RelayEnvelope` JSON objects and sent over a WebSocket. Large frames (up to 100MB) are supported between the relay and the app.

## 📄 License
Designed and developed for high-speed mobile cloud sharing.
