## What & why

<!-- Link the issue (Closes #…). If it works around an engine bug, link the jrouwe/JoltPhysics issue. -->

## Checklist

<!-- Tick what applies, delete what doesn't. -->

- [ ] The generator, `c/api-headers.txt` or the Jolt submodule changed: ran `./gradlew generateApi`, and `apiCheck` passes
- [ ] The generator changed: `./gradlew -p build-logic test` passes (`-Papigen.update` after a deliberate change to its output)
- [ ] User-visible: added a line under `[Unreleased]` in CHANGELOG.md, updated the docs
