# Streammania

A clean, Apple TV-style IPTV player for Android TV, Google TV, Fire TV and Android phones, using an Xtream Codes login.
The same app gives TVs a remote-friendly layout and phones a touch layout.

- **Profiles** – everyone gets their own favourites, Continue Watching and resume points; "Who's watching?" appears at start when there's more than one
- **Kids profiles and PIN lock** – kids profiles only see the categories you tick (child-friendly ones are picked to start with); a 4-digit PIN locks any profile, and once a grown-up profile has one, kids can't add or edit profiles or sign out
- **Hide categories** – Settings → Categories hides the ones you never watch, everywhere including search
- **Home** – opens first: a featured banner, Continue Watching, your channels, My List and the latest movies and series
- **Live TV** – categories, channel logos, now/next with progress, quick channel list, number keys to jump to a channel
- **Live preview (TVs)** – the highlighted channel plays muted in a small window in Live TV and the guide (Settings → Live preview)
- **TiviMate-style guide (TVs)** – full screen, with the highlighted programme and a live picture across the top and about ten channels and two and a half hours underneath; move left and right to scroll through the day, pick a group or day from the buttons above the channels (Settings → Guide and channel list for bigger rows)
- **Mini guide over live TV (TVs)** – press OK (or the Guide button) while watching: the picture keeps playing while you browse what's on now and next on nearby channels
- **Pause and rewind live TV** – on channels with catch-up, play/pause pauses and rewind / fast-forward skip back 10 seconds and on 30, as far back as your provider keeps, using your provider's own rewind (nothing is saved to the device). Settings → Watching → Pause & rewind live TV
- **Search what's on** – search finds programmes in the guide this week as well as channels, films and series, with live, catch-up and reminder options
- **Open straight into TV** – Settings → Watching → When the app opens: Home, the last channel you watched, or the TV guide
- **Up Next and Recently Added** – Home's Up Next row has what you're part-way through plus the next episode of anything you finished; finished films and episodes get a tick; Recently Added shows your provider's newest films and series
- **Recently added groups** – Movies and Series each start with a Recently added group, newest first
- **Your channels, your way** – Settings → Channels: put favourite channels in your own order (used by ★ Favourites, the guide and channel up/down) and hide channels you never watch, per profile
- **Live Sport Now** – a Home row of matches on right now with the score and the channel showing them; pick one and it starts, or watch several together in multi-view
- **Score in the corner** – pin a match's live score so it stays on screen while you watch something else
- **Multi-view** – watch 2 or 4 channels at once and switch the sound between them (each picture uses one provider connection)
- **Richer film and series pages** – trailers, cast photos and More Like This (add your free TMDB key in Settings → App)
- **Play on TV** – on your phone tap the TV button on Home, pick your TV and type the code it shows; anything you play then goes to the TV, and your phone becomes its remote. Works on your home Wi-Fi only, while Streammania is open on the TV
- **Guide** – a programme grid for any category, a week back and a week ahead (a scrolling timeline on TV and phone, a day picker on both; on phones, open it from Live TV)
- **Catch-up TV** – on channels your provider records, play past programmes from the guide, start the current one from the beginning, or browse a channel's recordings day by day (from the channel list in the player)
- **Reminders** – pick a future programme in the guide and choose Remind me; a pop-up offers to switch over when it starts (while the app is open)
- **Movies and Series** – posters, details, seasons and episodes, resume where you left off, auto-play next episode
- **Favourites** – hold OK on any channel, movie or series
- **Live scores** – while watching, press ▶ on the remote (or the football button on a phone) for today's football scores; matches on the current channel are pinned to the top
- **Previous channel and Recently Watched** – ⏪ flips back, and Home shows the channels you watched last
- **Sleep timer, subtitles and preferred audio** – in Settings and in the player
- **Built-in updates** – the app tells you when a new version is out and installs it itself
- **Picture quality and frame rate** – the live info bar shows the resolution and the measured frame rate, e.g. 1080p · 50 fps
- **Match frame rate (TVs)** – switches the TV to 50Hz for UK channels and 24Hz for films so motion is smooth (Settings → Match frame rate). On a Fire TV, also turn on **Settings → Display & Sounds → Display → Match Original Frame Rate** if your model has it
- **Sign in from your phone** – the TV sign-in screen shows a QR code; scan it on a phone on the same Wi-Fi and type the login there. It goes straight to the TV and never leaves your network
- **My teams** – follow clubs (Settings → My teams) for kick-off, goal and full-time pop-ups while the app is open, and their live or next match on Home
- **One poster per film** – when your provider lists a film or show several times (HD, FHD, 4K), Streammania shows it once with a quality badge and asks which quality when you press play; resume points carry across qualities. Settings → Quality to play can always pick the best, FHD or HD instead, and Combine versions turns this off
- **Other video players** – choose VLC, MX Player or any video app in Settings → Video player to play everything there, or pick **Open in another player** from the player's options when one stream misbehaves
- **Where's it on?** – pick any match in the live scores or on Home and Streammania searches your channels and sports guide for it; OK plays it
- **Picture-in-picture (phones)** – press Home while watching, or the picture-in-picture button, to keep watching in a small window
- **Screensaver (TVs)** – after a few idle minutes, a slideshow of movie and series artwork with the time; any button returns (Settings → Screensaver)
- **Search** – across everything your provider offers
- **Settings** – grouped into Profile, Watching, TV, Connection and App, with account status and expiry alongside

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
   `https://github.com/michaelgillespie88/ember-tv/releases/latest/download/Streammania.apk`
   (the older `.../EmberTV.apk` link and Downloader codes made for it still work)
2. Choose **Install**, then open **Streammania** and sign in with your provider's server address, username and password.

**Android phone**
1. Open the link above in the phone's browser and download the APK.
2. Open the download and allow your browser to install apps when Android asks.
3. On a phone, swipe up or down while watching live TV to change channel, tap for controls, double-tap the sides to skip, and press and hold anything to add it to your favourites.

The app checks for new versions itself and offers to install them (or use **Settings → Check for updates**).
You can also download the latest APK again and install over the top. Your login, profiles and favourites are kept.
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
