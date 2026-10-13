# .github/workflows/

CI pipelines. Every job that needs Jolt builds it, with the C API, for its own target: there are no prebuilt
libraries to fetch. See [Caches](#caches) for what is cached where.

| Workflow | Triggers | What it does |
| :--- | :--- | :--- |
| [`ci.yml`](ci.yml) | **push to `main`** and **every PR**, path-filtered per platform; **manual dispatch** (job picker) | One job per platform (jvm matrix / js / wasm / ios / android). Each builds the native library once, then builds and tests the Kotlin library on it. See [Running CI](#running-ci). |
| [`status-{jvm,js,wasm,ios,android}.yml`](status-jvm.yml) | `workflow_run` after **CI** completes on `main` | Reflect each platform job's conclusion from the latest `main` CI run into their own conclusion, powering the per-platform README badges. A job *skipped* by the path filter counts as passing: only a real failure turns a badge red. |
| [`pages.yml`](pages.yml) | PRs touching build config or the web sample (see its `paths:` filter) / **published release** / manual dispatch | Builds the web sample and the Dokka API reference. PRs only validate; a **published release** (or dispatch) deploys them to GitHub Pages, the sample at the site's root and the reference under `/api`, and a release also archives that version's docs on the orphan `docs-archive` branch for re-publishing under `api/older/<version>`: see [Versioned API docs](#versioned-api-docs). |
| [`publish.yml`](publish.yml) | tag matching `[0-9]*` / manual dispatch | Releases to Maven Central, then cuts the matching GitHub release with that version's CHANGELOG section as its notes. See [Releasing](#releasing) below. |

## Running CI

The `changes` job maps the PR's (or push's) changed files to platforms with
[`.github/scripts/ci-changes.sh`](../scripts/ci-changes.sh), and only those jobs run:

| Changed | Runs |
| :--- | :--- |
| `*.md`, `docs/`, the other workflows, issue templates | nothing (only `ci-gate`, so the PR still gets a status) |
| `c/` (but the two below), `build-logic/` | everything |
| `c/kolt/jni/`, `kotlin/*/src/jniMain/` | jvm, android |
| `c/kolt/web/`, `kotlin/*/src/{web,js,wasmJs}*`, `karma.config.d/` | web-runtime → js + wasm |
| `desktop/`, `kotlin/*/src/jvm*` | jvm |
| `kotlin/*/src/{native,ios}*` | ios |
| `kotlin/*/src/android*` | android |
| anything else (`commonMain`, `commonTest`, the `jolt` submodule, Gradle files, `ci.yml`, …) | everything |

Try it locally: `printf '%s\n' c/kolt/jni/KoltJni.cpp | .github/scripts/ci-changes.sh -`.

What each job runs:

| Job | Runner | Runs |
| :--- | :--- | :--- |
| `jvm` | macOS arm64, Linux x64/arm64, Windows x64/arm64 | `:desktop:cmakeBuild`, `:desktop:test` (the loader), `:kotlin:kolt:jvmTest` (Jolt, through `libkolt` loaded out of its runtime jar). On the Mac also the generator's fixture test, `apiCheck` and the Kover report (an artifact; no threshold). On Linux, a check that `libkolt.so` needs no C++ runtime. |
| `web-runtime` | Linux | `cmakeBuild_wasm`: `kolt.js` and `kolt.wasm`, handed to the next two as an artifact (`KOLT_WASM_PREBUILT`). |
| `js`, `wasm` | Linux | `jsBrowserTest`, `wasmJsBrowserTest`, in headless Chrome. |
| `ios` | macOS 26 | compiles `iosArm64`; `iosSimulatorArm64Test` in whichever iPhone the runner has (`-PiosSimulatorDevice`). |
| `android` | Linux | `assemble`, then `connectedAndroidDeviceTest` in an x86_64 emulator. |

- **Main runs too**: pushes to `main` run the same filtered jobs. That is what keeps the caches
  warm: a PR can only restore caches saved by its own ref or by `main`.
- **Concurrency**: a new push to a PR cancels the in-flight run for that ref
  (`cancel-in-progress`), so only the latest commit is built.
- **External (fork) PRs** wait for a maintainer to click **"Approve and run"**. This is
  GitHub's native fork-PR approval (*Settings → Actions → General → Fork pull request workflows
  from outside collaborators → "Require approval for all outside collaborators"*), **not**
  anything in the workflow. Collaborators' PRs run with no approval step.
- **Manually**: *Actions → CI → Run workflow* (`workflow_dispatch`) with a `jobs` input to
  pick `all` / `jvm` / `web` / `ios` / `android`. Handy for re-checking one platform. Dispatch
  runs don't enforce the merge gate.

### The merge gate

`ci-gate` is a tiny aggregator job (`needs:` every platform job, `if: always()`) that fails if the
`changes` filter failed or any platform job failed or was cancelled; jobs the filter skipped pass.
Protect `main` to require `ci-gate` (*Settings → Branches*), so a PR can't merge until the selected
jobs are green.

To change which jobs are required, edit the `ci-gate` `needs:` list and the branch-protection
`required_status_checks.contexts` together.

## Caches

The repo gets 10 GB of Actions cache; past that, GitHub evicts least-recently-used entries. A run can
restore caches from its own ref and from `main` only, so the rules are:

- **Gradle** ([`setup-gradle-cached`](../actions/setup-gradle-cached/action.yml)): written only by `main`;
  transforms, JDKs, dependencies and build-cache excluded.
- **emsdk** ([`setup-emsdk`](../actions/setup-emsdk/action.yml)): saved on a miss by any ref, keyed on
  `emsdkVersion`. Once `main` has it, PRs hit and never save.
- **AVD** (2+ GB, fixed key): saved only by `main`.
- **publish.yml** only restores: a tag ref's caches are invisible to every other run.

Jolt's own compile isn't cached: a few minutes per target.

Check usage with `gh cache list --sort size_in_bytes` and `gh api repos/{owner}/{repo}/actions/cache/usage`.

## Releasing

The publish workflow is a two-phase pipeline:

1. **`build-natives`**: matrix job (macOS arm64, Linux x64/arm64, Windows x64/arm64) that
   runs `:desktop:cmakeBuild` to produce `libkolt.{dylib,so}` / `kolt.dll` for each host, uploaded
   as `c-<id>` artifacts.
2. **`publish`**: runs on `macos-latest` (needed for the iOS klibs). Downloads all `c-*`
   artifacts via `merge-multiple: true` into `c-artifacts/<id>/`, then runs
   `publishToMavenCentral` with `-PcArtifactsDir=...`, so each `:desktop:runtime-<id>` jar ships
   its platform's library; a runtime jar without one refuses to publish.

What goes to Maven Central, under `io.github.erkko68.kolt`:

| Artifact | Carries |
| :--- | :--- |
| `kolt` (+ `kolt-jvm`, `kolt-android`, `kolt-iosarm64`, `kolt-iossimulatorarm64`, `kolt-js`, `kolt-wasm-js`) | the Kotlin API. The iOS klibs embed Jolt and the C API; the AAR has `libkolt.so` per ABI. |
| `kolt-desktop` | `KoltLoader`, which `kolt-jvm` depends on; its metadata picks the runtime jars. |
| `kolt-runtime-{macos-arm64, linux-x64, linux-arm64, windows-x64, windows-arm64}` | one platform's `libkolt`. |

The upload is validated, then **waits on [central.sonatype.com](https://central.sonatype.com/publishing/deployments)
for "Publish"**: a version on Maven Central can't be changed or removed, so that last click is by hand.

`kolt.js` and `kolt.wasm` aren't in the web klibs (webpack never sees a klib's resources): the
GitHub release carries them as assets.

### Versioned API docs

The docs job labels the site with the version (the tag's, or `VERSION_NAME` from `gradle.properties`)
and Dokka's versioning plugin renders a version dropdown. Only a published release goes live: PRs
build the site and throw it away, so the deployed docs always describe a released version. Released
versions live as `<version>/` directories on the orphan **`docs-archive`** branch: a deploying run
clones it into `build/previousDocs` before generating, but only a **release** writes back: it
force-pushes a single fresh commit with the tag's docs added (a store, not a history, otherwise the
repo would gain a full copy of the site per build). Each version is archived once, built from its tag.

GitHub Pages caps one site at 1 GB, so the archive keeps one version per minor line (its newest
patch, as patches don't change the API) for the eight newest lines; the tags stay, so anything else
can be rebuilt.

The archive starts empty, so the first release shows no dropdown: it appears from the second one on.

### How to cut a release

```bash
# 1. Bump VERSION_NAME in gradle.properties, move CHANGELOG's [Unreleased] under the version, commit.
# 2. Tag (no `v` prefix: the workflow's tag filter is [0-9]*).
git tag -a 0.1.0 -m "Release 0.1.0"
git push origin 0.1.0
```

The tag's name becomes the published version (passed to Gradle as `-PVERSION_NAME=${tag}`),
so the tag and `gradle.properties` should match.

Once Maven Central succeeds, the last steps create the **GitHub release** for that tag, using the
tag's own `## [<version>]` section of [CHANGELOG.md](../../CHANGELOG.md) as the release notes, and
dispatch `pages.yml` on the tag to deploy and archive its docs. They run only on a tag push (a
`workflow_dispatch` run has no tag to hang a release off), and if the changelog has no section for
that version it logs a warning and falls back to GitHub's generated notes rather than publishing an
empty release. Re-running a publish for a tag that already has a release will fail on this step:
delete the release first, or edit it by hand.

### Set up once

1. Register the `io.github.erkko68` namespace on central.sonatype.com (signing in with GitHub verifies it).
2. Add the secrets below (*Settings → Secrets and variables → Actions*).
3. *Settings → Pages → Source*: **GitHub Actions**.

| Secret | Purpose |
| :--- | :--- |
| `MAVENCENTRAL_USERNAME` / `MAVENCENTRAL_PASSWORD` | Central Portal user token. |
| `SIGNING_KEY` | ASCII-armored PGP private key (`gpg --export-secret-keys --armor <id>`); its public half on `keyserver.ubuntu.com`. |
| `SIGNING_KEY_ID` | Last 8 hex chars of the key fingerprint. |
| `SIGNING_PASSWORD` | PGP key passphrase. |

The vanniktech publish plugin reads them as `ORG_GRADLE_PROJECT_*` env vars; signing
activates automatically because the convention plugin gates on `signingInMemoryKeyId`
([build-logic/src/main/kotlin/kolt-publish.gradle.kts](../../build-logic/src/main/kotlin/kolt-publish.gradle.kts)).

### Manual / re-run

`workflow_dispatch` accepts a `version` input: useful for re-publishing a botched release
under a new version without retagging. The dispatched run still requires the `build-natives`
matrix to succeed.
