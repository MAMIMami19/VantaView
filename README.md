# VantaView

**IPTV & VOD player for Android phones, tablets, Android TV and Fire OS.**

VantaView is only a player. It does not include, host or recommend any channels, films or series.
You add your own playlists.

> Early test build. This is the first slice of the app, not the finished design.

## What this test build does

- Terms screen on first launch (Agree / Disagree)
- Add a playlist: Smart Add (paste your provider's message), M3U link, or Xtream Codes login
- Syncs channels into a local database; Xtream passwords are encrypted with the Android Keystore
- Live TV: categories, search (names or numbers), favourites, now and next on each channel; side-by-side layout on TV and tablets
- TV Guide: timeline from yesterday to a week ahead, from the playlist's own guide (Xtream or the M3U's url-tvg)
- Optional free channels from [Free TV](https://github.com/Free-TV/IPTV), added only if you choose
- Player: Media3 ExoPlayer, automatic reconnect when a stream drops, channel up/down
- Works with touch and with a TV remote

Not in yet: reminders, catch-up, profiles, recording, downloads, Stalker/MAC, add-ons, settings.

See [docs/FEATURES.md](docs/FEATURES.md) for the feature checklist: what is built and what is still to come.

## Download

Get `VantaView-test.apk` from the [test-latest release](../../releases/tag/test-latest).
Every push to `main` rebuilds it with GitHub Actions.

## Build it yourself

Open the folder in Android Studio and run, or with Gradle 8.11+ and JDK 17:

    gradle assembleDebug

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

"VantaView" and the VantaView logo are not covered by that licence. Please don't use the name or
logo for modified builds.

## Credits

- [Free TV](https://github.com/Free-TV/IPTV): the optional free channels list, fetched from its GitHub page. Thanks to its maintainers and contributors.
- [epgshare01](https://epgshare01.online): the TV guide used by the Free TV list, fetched only for the countries you pick.
- [Stream Goat](https://github.com/sherazi5110/stream-goat) by Syed Ali Hamza (GPL-3.0): the M3U
  parser and Xtream login check are adapted from its `LiveTv.kt`.
- [CloudStream](https://github.com/recloudstream/cloudstream) (GPL-3.0), upstream of Stream Goat.
- AndroidX, Jetpack Compose, Media3, Room, Hilt, OkHttp, Coil, Kotlin (Apache-2.0).
