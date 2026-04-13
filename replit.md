# Multi Plus

## Overview
Multi Plus is a Multi-Account Switcher for Facebook, originally an Android application. It is served as a web preview via a simple Node.js static file server.

## Tech Stack
- **UI**: Vanilla HTML5, CSS3, JavaScript (`index.html`)
- **Server**: Node.js HTTP server (`server.js`)
- **Android source**: Java (`MainActivity.java`, `ProxyVpnService.java`) — not compiled in Replit

## Project Structure
- `index.html` — Main dashboard UI (account list, 2FA generator, proxy settings)
- `inject.js` — Script injected into Facebook pages in the Android app
- `server.js` — Node.js static file server (serves `index.html` on port 5000)
- `MainActivity.java` — Android main activity (not used in web context)
- `ProxyVpnService.java` — Android VPN service (not used in web context)
- `AndroidManifest.xml` — Android manifest
- `app-build.gradle`, `build.gradle`, `settings.gradle` — Gradle build configs

## Running Locally
The app is served by `server.js` on port 5000:
```
node server.js
```

## Deployment
Configured as autoscale deployment running `node server.js`.
