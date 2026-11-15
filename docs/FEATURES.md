# VantaView feature checklist

Every agreed feature, grouped by page. `[x]` means it's built in the test build; it still needs testing on a phone and a TV. Items marked **(later)** were planned for after the first release. Notes in *italics* say what's still missing from a part-built item.

This file is ticked off in the same commit that finishes a feature.

## Setup and first launch

- [x] Splash screen with logo and loading bar
- [x] Terms: Agree or Disagree only (Disagree closes the app)
- [ ] First launch order: Terms, Create profile, Add Playlist, Home *(Add Playlist is a prompt on Home, not its own step)*
- [x] Skip Choose profile when there's only one profile
- [x] Update notice on launch

## Profiles

- [x] Create, edit and delete profiles
- [x] Built-in neon avatars
- [x] Optional 4-digit PIN
- [x] Kids profiles with an age limit and no Settings
- [ ] Separate favourites, watchlist, history, Continue watching and settings per profile *(no watchlist yet)*
- [x] Copy sources from the main profile
- [ ] Share a title to another profile (goes to their Library, with a message)

## Sources (Add Playlist)

- [x] Xtream Codes
- [x] M3U link or file
- [x] Stalker / MAC portal
- [x] Smart Add (paste a provider message)
- [x] Optional EPG link per source
- [x] Free channels (Free TV list, country picker, daily refresh, YouTube/Twitch/Dailymotion removed)
- [x] Addon links **(later)**
- [ ] Local files **(later)**
- [ ] Add from phone by QR code **(later)**
- [ ] Jellyfin server **(later)**

## Home

- [x] Clock and date
- [ ] Live TV, Movies, TV Series and Sports tiles *(TV Guide tile instead of Sports)*
- [x] Mini player of last channel, muted, with unmute
- [x] Continue watching row
- [x] Favourite channels row
- [x] NEW SEASON and COMING SOON badges, new season message
- [ ] Shared with you message
- [x] Weather by the clock with 5-day forecast **(later)**

## Live TV and guide

- [x] Categories, channel list and live preview
- [x] Now, Next and After
- [x] Timeline guide view
- [x] Full programme descriptions and scrolling long titles
- [x] Search, sort and edit favourites
- [x] Reminders for programmes not yet started (1/5/10/15 min, default 5)
- [ ] Category order and visibility per source

## Catch-up

- [ ] Catch-up page: groups, channels, days, past programmes
- [x] Watch from start in the guide
- [ ] Search past programmes

## Player

- [x] Live: previous/next channel, remote Up/Down changes channel
- [ ] Live: pause and rewind with Go live
- [x] Live: Now/Next bar and quick channel list
- [x] Audio, subtitles and picture size
- [ ] Smart anti-buffering with Medium buffer by default *(Small, Medium and Large buffer sizes, Medium by default; no automatic adjusting yet)*
- [x] Auto-reconnect with "Reconnecting" message
- [x] VOD: ±10s, speed, Episodes, resume
- [ ] Skip intro and Next episode countdown
- [x] Controls hide after 5 seconds (adjustable)
- [x] Phone: swipe gestures, lock button, picture-in-picture
- [ ] Record from the player **(later)**

## Movies and TV Series

- [ ] Recently added banner (Nuvio style)
- [x] Genre tiles
- [x] Sort, Genre, Year and Source filters
- [ ] Posters-only grid (Stremio style)
- [ ] Info on focus on TV
- [ ] TMDB artwork and details (Bearer token) *(TMDB fills the Movies and TV Series tiles when the build has a key; details pages still use add-ons)*

## Details pages

- [x] Movie details: Play/Resume, Trailer, Watchlist, Watched
- [ ] Trailer auto-play after 20s with sound and mute
- [x] Auto-pick best source, Choose source in the menu
- [x] Cast row and More like this
- [ ] Actor page with Available and All tabs, sort and filters *(a person's page lists what they're in, from search or a details page's cast; no tabs, sort or filters yet)*
- [x] Series details: season tabs, episode cards, Resume S2 E4
- [ ] Coming soon seasons
- [ ] Download episode or whole season **(later)**

## Search

- [x] Channels, films, series, programmes and people
- [x] On-screen keyboard on TV, recent searches
- [x] Voice search where available

## Library

- [ ] Shared with you
- [x] Watchlist
- [ ] Downloads **(later)**
- [ ] Recordings **(later)**

## Sports (later)

- [ ] On now, later today and this week from the guide
- [ ] Event details with Remind me and Record
- [ ] Follow teams and leagues
- [ ] Channels listed per game

## Settings

- [x] Two panes on TV, list on phone, search settings
- [ ] Playback: buffer, trailers, reminders, controls, mini player, gestures *(trailers and reminders get their settings when those features are built)*
- [x] Appearance: Dark/Light/Follow device, accent colour, language
- [x] Subtitles and audio
- [x] Sources and storage, Free channels settings
- [x] Local backup and restore
- [x] Parental control and clear history, plus a shortcut to add, change or remove this profile's PIN
- [x] Check for updates (downloads the APK and opens the installer)
- [ ] Speed test
- [x] App tour and support
- [ ] Ambient mode
- [x] Terms, Credits, About
- [ ] Trakt sync **(later)**
- [ ] Connected servers: Jellyfin, Sonarr, Radarr **(later)**

## Suggested for the first release (not yet agreed)

- [x] Type a channel number on the remote
- [ ] Last channel button
- [ ] Guide time offset per source
- [ ] Open in another player (VLC, MX Player)
- [ ] Sleep timer
- [x] Auto-refresh sources on a schedule
- [ ] Stream info overlay
- [x] Start on last channel option
- [ ] Export a log file

## Built that wasn't on the original list

- [x] TV Guide page in the sidebar and a TV Guide tile on Home, with an On now row
- [x] Tap a guide programme to play the channel with its Now playing info
- [x] Now and next under each channel in Live TV
- [x] TV guide for the Free TV channels (only the countries you pick)
- [x] Countries filter for big playlists, with divider and event-only channels handled
- [x] Back button goes back a page; press Back twice on Home to exit
- [x] Sidebar scrolls when the screen is short (phone on its side)
- [x] Follows the device's orientation, with portrait player and channel list on phones
- [x] Double tap the player or a channel for full screen
- [x] Greeting that follows the time of day, weather glance, location asked on first launch
- [x] Backup password, send to phone by QR code, automatic backups (keeps 5)
- [x] Debrid accounts for add-on streams
- [x] Playback settings: mini player on Home on/off, remember picture size
- [x] Parental control: hide adult categories everywhere, mark your own, lock Sources, PIN unlocks until the app closes
- [x] Light ripple on the loading screen, and a ripple burst from the profile you pick (still with animations off)
- [x] Appearance extras: Black (OLED) theme, custom accent colour, text size, sidebar auto-hide switch, loading screen switch
- [x] Updates can offer test builds (switch in Settings › Updates)
- [x] AI subtitles (beta): on-device captions or English translation with whisper.cpp, model downloaded when chosen
- [x] Storage: space used by type; clear pictures, TV guide and update downloads; TV guide refresh setting
- [x] Search page in the sidebar and on Home; Library page (Watchlist, Continue watching, Watched, Reminders); films marked watched when finished
- [x] Reminder pop-ups in the app, notifications when it is closed
- [x] Favourite star on the left of each channel
- [x] TV: number keys jump to a channel in the player; Fire TV steps for allowing updates; no location prompt on TVs
- [x] Release 0.7.0 published
- [x] Release 1.0 published, with a permanent download link (releases/latest/download/VantaView.apk)
- [x] Checked on Android TV 9 (Fire TV base) and Google TV 14 emulators with the remote
- [x] Looks (Settings › Appearance): VantaView, Glass, Broadcast, Wave and Classic, with previews; each changes colours, background, menu (Broadcast and Classic have a top menu on wide screens) and the TV Guide; appearance is saved per profile
- [x] Movies and TV Series tiles: Watch now (Trending, Latest, Top rated), Genres, Decades, Themed and International, each opening a full page of titles; the app's own tile pictures
- [x] Console-style looks: Blades, Stack, Tiles, Fluent, Cross and Spotlight, each with its own Home dashboard on wide screens, plus its colours, background, menu, card corners and headings on every page
- [x] Stalker portals: films and series on the Movies and TV Series pages, the portal's own TV guide, and Smart Add spots a portal address with a MAC address; full MAG sign-in, portal logos, optional box details (credit: Stalker-Portal by Jitendra Unatti)
- [x] Release 1.0.1: fast non-debug builds (about 3 s to open on the TV emulators), logo shown on launch

## Before release

- [ ] Works without Google Play Services (Fire TV)
- [ ] Tested on a 1 GB Fire TV Stick with a 50,000+ channel playlist
- [ ] Signing key backed up
- [ ] Privacy page published
- [ ] Credits complete (Free TV, TMDB, TheSportsDB, Open-Meteo, libraries) *(all credited except TheSportsDB, which comes with Sports)*
