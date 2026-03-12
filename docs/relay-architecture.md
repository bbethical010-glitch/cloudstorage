# Relay Architecture

This app is designed around a simple rule:

1. The external SSD or pen drive attached to the phone is the source of truth.
2. The phone runs the storage node.
3. A public relay or reverse tunnel only provides internet reachability.
4. The relay should not become the primary file store.

## Why a relay is still needed

Direct phone-hosted access from anywhere usually fails on mobile networks because of:

1. Carrier NAT
2. No stable public IP
3. Cellular firewalls
4. No inbound port forwarding

So the missing piece is not cloud file storage. It is a public route to the phone.

## Recommended production shape

1. Phone runs the on-device Ktor node on port `8080`
2. Public relay exposes a route like:

```text
https://relay.example.com/node/<share_code>
```

3. Relay forwards traffic to the active phone session
4. Browser or app clients interact with the forwarded node console

## Current app config

The app reads these optional values from `local.properties`:

```properties
APP_LINK_HOST=<your app link host>
RELAY_BASE_URL=https://relay.example.com
```

`APP_LINK_HOST` is for invite links that open the app.

`RELAY_BASE_URL` is the public endpoint used to reach the mounted drive through your relay.

## What the relay needs to support

At minimum:

1. A public HTTPS endpoint
2. Share-code based routing
3. A forwarding tunnel to the active phone node
4. Streaming upload and download support

## What the relay does not need to do

1. Store user files permanently
2. Mirror the full drive into cloud storage
3. Replace the external drive as the actual storage layer

## Repo status

This repo now includes a first relay implementation in the `relay` module.

Run instructions are in `docs/relay-runbook.md`.
