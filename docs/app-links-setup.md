# Android App Links Setup

This app now supports two invite formats:

1. `https://<your-domain>/join?code=...`
2. `easystoragecloud://join?code=...` as a fallback for local testing

For production, use the HTTPS link so Android can open the invite directly inside the app.

## Fastest free option: GitHub Pages

The simplest free host is a GitHub Pages **user site**:

```text
https://<your-github-username>.github.io/join?code=...
```

This repository already contains a ready-to-publish Pages bundle in:

```text
github-pages/
```

Publish that folder into a separate repository named exactly:

```text
<your-github-username>.github.io
```

Then set:

```properties
APP_LINK_HOST=<your-github-username>.github.io
```

## Required `local.properties` value

```properties
APP_LINK_HOST=invite.yourdomain.com
```

That value is used in:

1. The invite link shared from the app
2. The Android manifest app-link host declaration

## What you need to host

You do not need a full website. You only need to host the Digital Asset Links file at:

```text
https://invite.yourdomain.com/.well-known/assetlinks.json
```

For GitHub Pages, that becomes:

```text
https://<your-github-username>.github.io/.well-known/assetlinks.json
```

## `assetlinks.json` template

Replace the fingerprint when you move from debug to your real release signing key.

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.pratham.cloudstorage",
      "sha256_cert_fingerprints": ["REPLACE_WITH_RELEASE_SHA256"]
    }
  }
]
```

## How to get the signing fingerprint

For a release keystore:

```text
keytool -list -v -keystore your-release-key.jks -alias your-alias
```

Use the `SHA256:` value in `assetlinks.json`.

## Result

Once the domain is configured and verified:

1. The host taps `Share invite`
2. The recipient opens the HTTPS invite from WhatsApp, Messages, email, or another app
3. Android routes the link directly into Easy Storage Cloud
4. The recipient sees the in-app technical invite console and joins from there

## Notes

1. During development, the custom scheme still works even without domain verification.
2. Verified app links only work reliably with a real HTTPS host and a valid `assetlinks.json`.
3. GitHub Pages is sufficient because it gives you a real HTTPS host, even though it is free.
