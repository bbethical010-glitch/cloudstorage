# GitHub Pages Host For Easy Storage Cloud

This folder is the minimal static host for Android App Links.

It is **not** a separate product and **not** the main user experience. The real invite experience stays inside the Android app. This host only provides:

1. `/.well-known/assetlinks.json` for Android verification
2. `/join` as a small fallback route if app links are not yet verified
3. `.nojekyll` so GitHub Pages serves `.well-known` correctly

## Important GitHub Pages rule

For Android App Links with GitHub Pages, use a **user site** repository:

```text
<your-github-username>.github.io
```

Do **not** use a project site like:

```text
<your-github-username>.github.io/AndroidCloudStorageApp
```

That path-based site will not work correctly for `/.well-known/assetlinks.json` on the root host.

## Setup steps

1. Create a new public GitHub repository named exactly:

```text
<your-github-username>.github.io
```

2. Copy the contents of this folder into the root of that repository.

3. Commit and push.

4. In this Android project, add to `local.properties`:

```properties
APP_LINK_HOST=<your-github-username>.github.io
```

5. Rebuild and reinstall the Android app.

## Current `assetlinks.json`

The shipped `assetlinks.json` contains the **debug signing fingerprint** for local testing:

```text
53:54:DC:D9:8E:CA:9D:AC:43:13:09:2B:98:F3:06:F3:9E:E5:A7:8A:89:21:45:35:2B:4C:09:48:18:95:3F:CF
```

Before release, replace it with your **release signing SHA256** fingerprint.

## Result

Once GitHub Pages is live and `APP_LINK_HOST` matches:

1. The app shares `https://<your-github-username>.github.io/join?code=...`
2. Android can verify the host with `assetlinks.json`
3. Opening the link routes users directly into Easy Storage Cloud
4. The in-app invite console handles the rest
