# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). A release's section is its GitHub
release's notes (`.github/workflows/publish.yml`).

## [Unreleased]

### Added
- **A sample app** in `samples/`: a wall of cubes to push over, simulated by kolt and rendered by filament-kmp in
  Compose Multiplatform, on Android, iOS, the desktop and the web. Its web build is the project's site,
  [erkko68.github.io/kolt](https://erkko68.github.io/kolt/).

### Changed
- **The API reference is under `/api`** of the site, [erkko68.github.io/kolt/api](https://erkko68.github.io/kolt/api/),
  where it was the site's root.

## [0.1.0] — 2026-10-13

### Added
- **Jolt Physics 5.6.0 for Kotlin Multiplatform**: Jolt's C++ API, generated, in `commonMain`, on Android, iOS, the JVM
  (macOS arm64, Linux and Windows x64 and arm64) and the web (JS and Wasm).

[Unreleased]: https://github.com/Erkko68/kolt/compare/0.1.0...HEAD
[0.1.0]: https://github.com/Erkko68/kolt/releases/tag/0.1.0
