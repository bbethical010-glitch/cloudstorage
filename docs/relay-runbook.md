# Relay Runbook

This repo now contains a relay service in the `relay` module.

## What it does

1. Accepts a persistent WebSocket connection from the phone node
2. Exposes public HTTP routes like `http://relay-host:8787/node/<share_code>`
3. Forwards HTTP requests over the tunnel to the phone
4. Returns the phone node response back to the public client

Files still stay on the external SSD or pen drive attached to the phone.

## Run locally

From the project root:

```bash
./gradlew :relay:run
```

The relay listens on port `8787` by default.

You can change the port with:

```bash
PORT=9090 ./gradlew :relay:run
```

Health check:

```bash
curl http://127.0.0.1:8787/health
```

## Point the Android app to the relay

Set this in `local.properties`:

```properties
RELAY_BASE_URL=http://YOUR_RELAY_HOST:8787
```

Then rebuild the app:

```bash
./gradlew :app:assembleDebug
```

Inside the app:

1. Select the external storage folder
2. Start the node
3. Make sure the relay base URL matches your running relay
4. Share the invite or open the public route shown in the app

## Public routes

The relay exposes:

```text
/health
/agents
/node/<share_code>
/node/<share_code>/<path>
```

`/agents` returns the currently connected share codes.

## Current limitations

This is the first relay implementation, not the final production tunnel.

Current gaps:

1. No authentication between relay and phone yet
2. Request and response bodies are buffered in memory
3. Large file streaming is not chunked yet
4. No rate limiting
5. No persistent device registry
6. No TLS termination inside this repo

## Production next steps

Before public release, add:

1. Relay auth tokens bound to share codes
2. Chunked or streamed upload and download forwarding
3. HTTPS deployment behind a real domain
4. Session expiry and heartbeat monitoring
5. Access control for shared nodes
