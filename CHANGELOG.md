## [0.1.14-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.13-alpha...v0.1.14-alpha) (2026-10-30)

### 🐛 Bug Fixes

* the TV no longer pauses a few seconds after a song change ([575f99c](https://github.com/ca-za/morphe-yt-dlna/commit/575f99c0253d8469e206940b107593df83a22c3c))
* lyrics on music videos in time, and no seek hitch after a song starts ([f9469f0](https://github.com/ca-za/morphe-yt-dlna/commit/f9469f0f71320ee085feeeca102c7dc5efa0435b))
* another app taking the audio focus no longer pauses the TV ([1f4890b](https://github.com/ca-za/morphe-yt-dlna/commit/1f4890b95fb4be1572ff16cc01311291781ef3e8))

## [0.1.13-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.12-alpha...v0.1.13-alpha) (2026-10-30)

### 🐛 Bug Fixes

* rooms added or removed on the TV show in the cast dialog ([5bb8a32](https://github.com/ca-za/morphe-yt-dlna/commit/5bb8a3280979f78f6f01452dcb9e19edd8842d51))

### 🚀 Updated App Support

* YouTube 21.30.216 and YouTube Music 9.30.53 recommended ([9e58c4e](https://github.com/ca-za/morphe-yt-dlna/commit/9e58c4e025a235707fa34dabca8f09daf26de8c8))

## [0.1.12-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.11-alpha...v0.1.12-alpha) (2026-10-30)

### 🚀 Updated App Support

* App versions of the official Morphe patches 2.0.0 (#5) ([bc9c3bc](https://github.com/ca-za/morphe-yt-dlna/commit/bc9c3bcb95e75210d3f37a1e45d6577945e6df71))

## [0.1.11-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.10-alpha...v0.1.11-alpha) (2026-10-30)

### 🐛 Bug Fixes

* DLNA cast patches YouTube 21.23 ([f690c55](https://github.com/ca-za/morphe-yt-dlna/commit/f690c5573dd526c383f3a5760206c35fa69fab82))

## [0.1.10-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.9-alpha...v0.1.10-alpha) (2026-10-30)

### ✨ New Features

* the stream's frame rate in the DASH and HLS manifests ([3216a47](https://github.com/ca-za/morphe-yt-dlna/commit/3216a479eef9d6726e4913cf4a4288cb96296f49))
* the cast dialog's volume slider shows its value ([67e4eaf](https://github.com/ca-za/morphe-yt-dlna/commit/67e4eaf55f732ca7b224d53aeed44440f1681e2e))
* a volume slider in the cast dialog ([33a0685](https://github.com/ca-za/morphe-yt-dlna/commit/33a068546872181ac75a1343e9249292056cac1d))

## [0.1.9-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.8-alpha...v0.1.9-alpha) (2026-10-29)

### ✨ New Features

* DLNA app icon, an optional icon for YouTube and YouTube Music ([f8efe33](https://github.com/ca-za/morphe-yt-dlna/commit/f8efe333c97e72bc483fafe964c8ee7f63c92a20))

## [0.1.8-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.7-alpha...v0.1.8-alpha) (2026-10-29)

### 🐛 Bug Fixes

* YouTube Music: a song switched to video casts its music video, not the cover ([255eda1](https://github.com/ca-za/morphe-yt-dlna/commit/255eda1cc28b8d49718a75725e4d650fe081c488))
* albums and playlists of songs: the playlist reaches the renderer, songs change cleanly ([6745d87](https://github.com/ca-za/morphe-yt-dlna/commit/6745d8764138648f139243c1d32c4cbfc8ea61e3))

## [0.1.7-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.6-alpha...v0.1.7-alpha) (2026-10-29)

### ✨ New Features

* chapters and loudness in the renderer extras ([64006b3](https://github.com/ca-za/morphe-yt-dlna/commit/64006b353f77d79091e41f1e97d7f2d80d7caafb))
* rooms dialog tells why the renderer's rooms last stopped (lastEnd) ([e129be5](https://github.com/ca-za/morphe-yt-dlna/commit/e129be530f4af898a0fbeb6887952839eeef320a))

## [0.1.6-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.5-alpha...v0.1.6-alpha) (2026-10-29)

### ✨ New Features

* remote control of DLNA sessions; optional parts as patches of their own ([7a2d0ab](https://github.com/ca-za/morphe-yt-dlna/commit/7a2d0abfdf9db0dbe8b851e71e14aad16d78edf1))
* renderer rooms as built: mute, fault messages, volume fallback ([0b58d8c](https://github.com/ca-za/morphe-yt-dlna/commit/0b58d8c26e3e324a9a023da7310c3e4bbbec42df))
* rooms of a renderer that feeds Sonos itself (Zones service, draft) ([b13ce50](https://github.com/ca-za/morphe-yt-dlna/commit/b13ce502a117ec6fc626dba0df9d0a488aa3d56f))
* YouTube keeps the player controls on screen while casting ([d3c680a](https://github.com/ca-za/morphe-yt-dlna/commit/d3c680a787bbc0207dc8a10b8ad2c637817a4f71))

### 🐛 Bug Fixes

* rooms dialog acts on the current room state, one change at a time ([1d20d23](https://github.com/ca-za/morphe-yt-dlna/commit/1d20d239dbc4d27754ddcf78350b1c0278adf165))
* Music cover page: darker bottom, color fade between songs, no black flashes ([f44e595](https://github.com/ca-za/morphe-yt-dlna/commit/f44e595ec3a83e5427134954e60d41e7ebc83d19))

## [0.1.5-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.4-alpha...v0.1.5-alpha) (2026-10-29)

### 🐛 Bug Fixes

* Sonos rooms in the device list's look ([67bf853](https://github.com/ca-za/morphe-yt-dlna/commit/67bf8534d696242742f5946cdfc20ca4ad94f748))

## [0.1.4-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.3-alpha...v0.1.4-alpha) (2026-10-29)

### ✨ New Features

* Sonos groups and rooms in the device list, group volume ([09224dd](https://github.com/ca-za/morphe-yt-dlna/commit/09224dd22c9dcecb1c425717e5d1149dd0efed9d))

## [0.1.3-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.2-alpha...v0.1.3-alpha) (2026-10-29)

### ✨ New Features

* Sonos room names in the device list; session search resumes after a reboot ([28375bf](https://github.com/ca-za/morphe-yt-dlna/commit/28375bf4782ef558b2ba82e8822348d5b3a9b1a0))

## [0.1.2-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.1-alpha...v0.1.2-alpha) (2026-10-29)

### ✨ New Features

* background session notifications for YouTube Music (own patch); extras for queued titles ([f284a7c](https://github.com/ca-za/morphe-yt-dlna/commit/f284a7c6d8cf437d05584941f6c46296cf258010))

## [0.1.1-alpha](https://github.com/ca-za/morphe-yt-dlna/compare/v0.1.0-alpha...v0.1.1-alpha) (2026-10-29)

### ✨ New Features

* lyrics and audio language for the renderer, Spotify look, portrait while casting ([22356b4](https://github.com/ca-za/morphe-yt-dlna/commit/22356b45c15dea4a5e788b3ee3a74ac46c342573))

## 0.1.0-alpha (2026-10-28)

First release with the new version numbering (formerly 1.0.0-alpha.1 to 1.0.0-alpha.9).

## [1.0.0-alpha.9](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.8...v1.0.0-alpha.9) (2026-10-28)

### 🐛 Bug Fixes

* captions for queued YouTube Music titles ([b40a4ed](https://github.com/ca-za/morphe-yt-dlna/commit/b40a4ed75ac0a10a2693f884ad72ea3e611ec8e7))

### ✨ New Features

* captions for renderers; fix seeking with the progress bar ([dc1b858](https://github.com/ca-za/morphe-yt-dlna/commit/dc1b858464ceb8be19f696f6bb9a809ee8604f94))

## [1.0.0-alpha.8](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.7...v1.0.0-alpha.8) (2026-10-28)

### ✨ New Features

* the user's commands drive the renderer; renderer extras; Music cover ([6cf4230](https://github.com/ca-za/morphe-yt-dlna/commit/6cf4230b5b0ed75f5d612891e00e0405ae010951))

## [1.0.0-alpha.7](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.6...v1.0.0-alpha.7) (2026-10-28)

### ✨ New Features

* observe player commands with their source (prototype) ([236336a](https://github.com/ca-za/morphe-yt-dlna/commit/236336a4aa18ac96f2c9e09696961370c7a2047d))

## [1.0.0-alpha.6](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.5...v1.0.0-alpha.6) (2026-10-28)

### ✨ New Features

* debug log with SponsorBlock diagnostics ([c89430f](https://github.com/ca-za/morphe-yt-dlna/commit/c89430f8182ce6e5d79611f30920acf0cc74db28))

## [1.0.0-alpha.5](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.4...v1.0.0-alpha.5) (2026-10-28)

### 🐛 Bug Fixes

* the renderer leads; Shorts and Morphe's own playback stay off the TV ([3652b00](https://github.com/ca-za/morphe-yt-dlna/commit/3652b00c35c146446fb48bb10d7a04f69768d077))

## [1.0.0-alpha.4](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.3...v1.0.0-alpha.4) (2026-10-28)

### ✨ New Features

* mark and remove renderers added by address ([d420206](https://github.com/ca-za/morphe-yt-dlna/commit/d420206e15fc17539050d83800a409d2ef5cc4ae))

## [1.0.0-alpha.3](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.2...v1.0.0-alpha.3) (2026-10-28)

### 🐛 Bug Fixes

* follow the app's media session without its notification ([cb4f7cf](https://github.com/ca-za/morphe-yt-dlna/commit/cb4f7cfa3f0ef076d2f25d40e0c81bf66c64715f))
* renderer follows skips right after the cast start ([29925c0](https://github.com/ca-za/morphe-yt-dlna/commit/29925c0f15499c4724891894d47e5d05042e3f77))

## [1.0.0-alpha.2](https://github.com/ca-za/morphe-yt-dlna/compare/v1.0.0-alpha.1...v1.0.0-alpha.2) (2026-10-27)

### 🐛 Bug Fixes

* Support the app versions Morphe recommends ([6f20d02](https://github.com/ca-za/morphe-yt-dlna/commit/6f20d02a165c7831f62b3c4d00dffc0bd080e35a))

## 1.0.0-alpha.1 (2026-10-27)

### 🐛 Bug Fixes

* Accept renderer events that arrive before the SUBSCRIBE response ([3d87f8a](https://github.com/ca-za/morphe-yt-dlna/commit/3d87f8a035743e2729e1869fcadbcd874715a1c0))
* Keep phone and TV in sync during fast or overlapping changes ([d509dc2](https://github.com/ca-za/morphe-yt-dlna/commit/d509dc2016eaac0ccfc3d4126c194d76434da284))
* Keep the song/video switch working while casting ([f5a9f2d](https://github.com/ca-za/morphe-yt-dlna/commit/f5a9f2de8bfdc540a9c956f15c706bcfc6b4fe3c))
* Look up the next title's details before announcing it to AVTransport renderers ([5b2180d](https://github.com/ca-za/morphe-yt-dlna/commit/5b2180d424d4861e3b7f4b0e6c4ba8af52bb345f))
* Renderer paused itself shortly after a cast started ([3e7090b](https://github.com/ca-za/morphe-yt-dlna/commit/3e7090b017069a6a56c5bd64de92f2b2745ddd4a))
* Resuming after a pause could leave the renderer paused ([6704385](https://github.com/ca-za/morphe-yt-dlna/commit/67043850616eb4b335a7e47f21e690835a12ee58))

### ✨ New Features

* Add DLNA cast patch for YouTube and YouTube Music ([77035b4](https://github.com/ca-za/morphe-yt-dlna/commit/77035b42106e99f99f692547e6b854d7a7e089ca))
* Album covers from Deezer and iTunes for YouTube Music ([fdc1a50](https://github.com/ca-za/morphe-yt-dlna/commit/fdc1a50c347f81afde3414b4530f4b70e931a064))
* Cast bar and volume keys for the TV ([dca6145](https://github.com/ca-za/morphe-yt-dlna/commit/dca61451b73eb9b1cc500c86a65d1bf45d3a6038))
* Cast live streams as HLS ([d9a5923](https://github.com/ca-za/morphe-yt-dlna/commit/d9a5923a1f0dd81d1d939080a0a296bf3623e576))
* Close the stream proxy between casts ([3b8114f](https://github.com/ca-za/morphe-yt-dlna/commit/3b8114f2b52a24d91ed90d4c720e8469af47135a))
* Follow the YouTube Music song/video switch ([152f38f](https://github.com/ca-za/morphe-yt-dlna/commit/152f38f843c09d1399b9672c11d9959a5a8faca2))
* Handle renderer events in the native proxy ([d60a09b](https://github.com/ca-za/morphe-yt-dlna/commit/d60a09b98c6e5403996818041bc060d58ead3206))
* Introduce the phone to renderers by device name and app ([61352dc](https://github.com/ca-za/morphe-yt-dlna/commit/61352dccaa00904f82f9da4ce402aa298027ffc8))
* Lower the YouTube player quality while casting ([cf25762](https://github.com/ca-za/morphe-yt-dlna/commit/cf25762324be605f7aad554800154a70b324f025))
* Mirror the app player to the renderer ([ae56b48](https://github.com/ca-za/morphe-yt-dlna/commit/ae56b48e5471629392b63d6ad971eb69ea61e0dc))
* Native Rust stream proxy ([f6c816c](https://github.com/ca-za/morphe-yt-dlna/commit/f6c816cec9bedd37ffa26a4f0f1c52b52bffa5da))
* Nothing plays visibly or audibly on the phone while casting ([633d7a9](https://github.com/ca-za/morphe-yt-dlna/commit/633d7a90927c2ed9e761fffe4d3c8127d826b1f9))
* Phone plays only the smallest audio track while casting ([73524d6](https://github.com/ca-za/morphe-yt-dlna/commit/73524d605b74548fe6bfa18704719c93cfa449ae))
* Send richer metadata and the next title to renderers ([91bc0ef](https://github.com/ca-za/morphe-yt-dlna/commit/91bc0ef781a7c1bc178e95665c5f7950709cab38))
* Send the queue as OpenHome playlist ([162d03f](https://github.com/ca-za/morphe-yt-dlna/commit/162d03fac7a6fbd1d819c600e975e61cdab8ae18))
* Serve HLS to renderers without DASH ([0e2feb1](https://github.com/ca-za/morphe-yt-dlna/commit/0e2feb16494e7d112455a29c2c33b764704d660f))
* Show the cast button for DLNA renderers found in the background ([4f9a2d3](https://github.com/ca-za/morphe-yt-dlna/commit/4f9a2d3ea4f09fe26b35dc9b00edfc8ca4f65d09))
* Support older app versions and require the official Morphe patches ([ed51d4e](https://github.com/ca-za/morphe-yt-dlna/commit/ed51d4e8e6a105e0a9440121edf2eb8164ad30ca))
* Support YouTube Music and cast the playing video ([8c6a08a](https://github.com/ca-za/morphe-yt-dlna/commit/8c6a08ae678931f9df87d280a7d266c141e6de69))
