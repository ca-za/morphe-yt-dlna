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
