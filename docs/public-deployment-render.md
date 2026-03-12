# Public Deployment on Render

This is the simplest public deployment path for the relay in this repo.

## What you are deploying

You are deploying only the relay service.

The relay gives the phone a public HTTPS entry point and forwards requests to the connected phone node over a WebSocket tunnel.

The files still stay on the external SSD or pen drive attached to the phone.

## Files already prepared

This repo now includes:

1. `relay/Dockerfile`
2. `relay/settings.gradle.kts`
3. `render.yaml`

## Deploy steps

1. Push this repo to GitHub
2. Sign in to Render
3. Create a new Blueprint service from the GitHub repo
4. Render will detect `render.yaml`
5. Create the service

The relay exposes:

```text
/health
/agents
/node/<share_code>
/node/<share_code>/<path>
```

## After Render gives you a URL

You will get a public URL like:

```text
https://easy-storage-relay.onrender.com
```

Put that value into:

```properties
RELAY_BASE_URL=https://easy-storage-relay.onrender.com
```

Then rebuild and reinstall the app.

Inside the app, the public route will become:

```text
https://easy-storage-relay.onrender.com/node/<share_code>
```

## Verification

After deployment:

1. Open `https://YOUR_RENDER_HOST/health`
2. Start the node on the phone
3. Open `https://YOUR_RENDER_HOST/agents`
4. Confirm your share code appears
5. Open `https://YOUR_RENDER_HOST/node/YOUR_SHARE_CODE/api/status`

Expected result:

```json
{"status":"online"}
```

## Important production note

The current relay is a working first deployment target, but not a hardened production service yet.

Before release, add:

1. Authentication between the phone and relay
2. Access control per share code
3. Better handling for large streamed uploads and downloads
4. Logging and rate limiting
5. A real custom domain if you want stable branded links
