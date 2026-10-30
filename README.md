# Ember TV

A clean, Apple TV-style IPTV player for Android TV, Google TV, Fire TV and Android phones, using an Xtream Codes login.
The same app gives TVs a remote-friendly layout and phones a touch layout.

- **Home** – opens first: a featured banner, Continue Watching, your channels, My List and the latest movies and series
- **Live TV** – categories, channel logos, now/next with progress, quick channel list, number keys to jump to a channel
- **Guide** – a programme grid for any category (on phones, open it from Live TV)
- **Movies and Series** – posters, details, seasons and episodes, resume where you left off, auto-play next episode
- **Favourites** – hold OK on any channel, movie or series
- **Search** – across everything your provider offers
- **Settings** – account status and expiry, TS/HLS switch, channel up/down direction, player identity, sign out

## Install on the TV

Every push to `main` builds a new APK automatically (see the **Actions** tab) and publishes it as the latest release.

**Fire TV Stick**
1. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
2. Allow it to install apps: **Settings → My Fire TV → Developer options → Install unknown apps → Downloader → On**.
   (If Developer options is missing: **Settings → My Fire TV → About**, press OK on the device name 7 times.)

**Google TV / Android TV**
1. Install **Downloader** (by AFTVnews) from the Play Store.
2. Allow it to install apps: **Settings → Apps → Security & restrictions → Unknown sources → Downloader → On**.

**Then, on either**
1. Open Downloader and enter:
   `https://github.com/michaelgillespie88/ember-tv/releases/latest/download/EmberTV.apk`
2. Choose **Install**, then open **Ember TV** and sign in with your provider's server address, username and password.

**Android phone**
1. Open the link above in the phone's browser and download the APK.
2. Open the download and allow your browser to install apps when Android asks.
3. On a phone, swipe up or down while watching live TV to change channel, tap for controls, double-tap the sides to skip, and press and hold anything to add it to your favourites.

Updating works the same way: download the latest APK again and install over the top. Your login and favourites are kept.
The version number is shown on the sign-in screen and in Settings.

## Remote controls

| Where | Button | Action |
|---|---|---|
| Anywhere | OK | Select |
| Anywhere | Hold OK | Add to / remove from favourites |
| Anywhere | Back | Jump to the top bar, then exit |
| Live TV | ▲ ▼ or CH+ / CH− | Change channel |
| Live TV | ◀ | Channel list |
| Live TV | OK | Show / hide info |
| Live TV | 0–9 | Go to channel number |
| Movies / episodes | ◀ ▶ (hold to go faster) | Skip back / forward |
| Movies / episodes | OK | Pause / play |
| Movies / episodes | ▼ | Audio and subtitle tracks |

## Notes

- Your login is stored only on the TV. Nothing about your provider is in this repository.
- The signing key in `app/ember.keystore` exists so updates install over older builds. It is only for personal sideloading.
- If channels buffer or refuse to start, try **Settings → Stream format**, then **Player identity**.
