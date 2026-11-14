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
- [ ] M3U link or file *(link only)*
- [ ] Stalker / MAC portal
- [x] Smart Add (paste a provider message)
- [ ] Optional EPG link per source *(the playlist's own guide link is used; no box to type one)*
- [ ] Free channels (Free TV list, country picker, daily refresh, YouTube/Twitch/Dailymotion removed) *(no daily refresh)*
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
- [ ] Now, Next and After *(Now and Next only)*
- [x] Timeline guide view
- [ ] Full programme descriptions and scrolling long titles *(descriptions shown, titles don't scroll)*
- [ ] Search, sort and edit favourites *(search only)*
- [ ] Reminders for programmes not yet started (1/5/10/15 min, default 5)
- [ ] Category order and visibility per source

## Catch-up

- [ ] Catch-up page: groups, channels, days, past programmes
- [ ] Watch from start in the guide
- [ ] Search past programmes

## Player

- [x] Live: previous/next channel, remote Up/Down changes channel
- [ ] Live: pause and rewind with Go live
- [x] Live: Now/Next bar and quick channel list
- [x] Audio, subtitles and picture size
- [ ] Smart anti-buffering with Medium buffer by default *(Small, Medium and Large buffer sizes, Medium by default; no automatic adjusting yet)*
- [x] Auto-reconnect with "Reconnecting" message
- [ ] VOD: ±10s, speed, Episodes, resume *(±10s and resume only)*
- [ ] Skip intro and Next episode countdown
- [x] Controls hide after 5 seconds (adjustable)
- [ ] Phone: swipe gestures, lock button, picture-in-picture *(swipe to change channel and double tap for full screen, with an off switch; no lock button or picture-in-picture)*
- [ ] Record from the player **(later)**

## Movies and TV Series

- [ ] Recently added banner (Nuvio style)
- [ ] Genre tiles
- [ ] Sort, Genre, Year and Source filters
- [ ] Posters-only grid (Stremio style)
- [ ] Info on focus on TV
- [ ] TMDB artwork and details (Bearer token)

## Details pages

- [ ] Movie details: Play/Resume, Trailer, Watchlist, Watched *(Play/Resume only)*
- [ ] Trailer auto-play after 20s with sound and mute
- [ ] Auto-pick best source, Choose source in the menu *(Choose stream only)*
- [ ] Cast row and More like this *(cast list only)*
- [ ] Actor page with Available and All tabs, sort and filters
- [x] Series details: season tabs, episode cards, Resume S2 E4
- [ ] Coming soon seasons
- [ ] Download episode or whole season **(later)**

## Search

- [ ] Channels, films, series, programmes and people
- [ ] On-screen keyboard on TV, recent searches
- [ ] Voice search where available

## Library

- [ ] Shared with you
- [ ] Watchlist
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
- [ ] Sources and storage, Free channels settings
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
- [ ] Auto-refresh sources on a schedule
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
- [x] Release 0.7.0 published

## Before release

- [ ] Works without Google Play Services (Fire TV)
- [ ] Tested on a 1 GB Fire TV Stick with a 50,000+ channel playlist
- [ ] Signing key backed up
- [ ] Privacy page published
- [ ] Credits complete (Free TV, TMDB, TheSportsDB, Open-Meteo, libraries) *(Settings › Help and about credits Free TV, epgshare01, Open-Meteo and the libraries; add TMDB and TheSportsDB when they are used)*
