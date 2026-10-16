# Danav Chat — Android app and backend

## Deployed services

- Cloudflare Worker API: `https://danav-chat.wahabxedits.workers.dev`
- Cloudflare D1 database: `danav-chat-db` (group-chat and expiring-status schema is applied).
- `/health` responds successfully. Protected endpoints require a signed-in user.

## Android app and refreshed interface

Release `1.1.0` (`versionCode 2`) includes Google sign-in, 1:1 and group chats, delivered/read ticks, online/last-seen and typing indicators, block/unblock, reply/forward/delete actions, multi-photo albums with full-screen previews, file/video/voice-note sharing, profile pages and avatar editing, 24-hour text/photo/video statuses with view tracking, push notifications, voice/video calls and call history, plus light/dark themes.

The interface has been refreshed with a four-item bottom navigation, dedicated Updates cards, redesigned conversation and group rows, a custom doodle wallpaper, tighter photo/album bubbles, avatar-backed seekable voice-note controls, animated screen transitions, subtle content/recording animations, and a clustered call-control panel. Layout adapts to both light and dark themes.

Background call delivery depends on Android notification and foreground-service permissions, device battery policies, network availability, and FCM delivery; force-stopped apps cannot be guaranteed to ring. The interface was compiled and lint-checked; real-device visual verification and signed-in two-account end-to-end testing remain outstanding.

## Project layout

- `android/` — Kotlin + Jetpack Compose app and local SQLite cache.
- `server/` — Cloudflare Worker API, D1 schema and Durable Object realtime/signalling.
- `google-services.json` — Firebase Android client configuration; it is not the Firebase Admin private key.
- `keystore/` — signing material is present in this private GitHub repository for reproducible releases. Keep the repository private; never publish or forward the signing files or passwords. The downloadable source ZIP omits the key material.

## Build

With JDK 17 and Android SDK platform/build tools installed, run from `android/`:

```sh
./gradlew :app:assembleRelease -PapiBase=https://danav-chat.wahabxedits.workers.dev
```

The APK is written to `android/app/build/outputs/apk/release/app-release.apk`. Future updates intended to install over this release must use the same private signing keystore; the source archive intentionally excludes the key and password file.

## Google Sign-In

Android package: `com.danav.chat`

Signing SHA-1:
`B4:38:7C:5E:31:D3:29:7D:C3:47:CF:49:9E:F5:07:37:C2:F4:49:9F`

If Google sign-in reports app/client configuration errors, create an Android OAuth client in the same Google Cloud project as the Web OAuth client, using the package and SHA-1 above. The OAuth Web Client ID is used by the app and backend. Never put its client secret in the Android app.

## Privacy limitation

Messages are protected in transit but are **not end-to-end encrypted**. Chat text is stored in D1 and Cloudinary media is delivered through retrievable asset URLs. Do not treat this app as providing E2EE or confidential/private media delivery.
