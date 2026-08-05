# AGENTS.md

Guide for AI agents (and humans) working on Restoid. Read this **before** touching
build config, CI, or the restic native build — it exists so we don't repeat past mistakes.

## What this project is

Restoid is an Android app (Kotlin + Jetpack Compose) that bundles **[restic](https://restic.net)**
as a native binary. restic is a Go program compiled per Android ABI into `librestic.so`,
dropped into `app/src/main/jniLibs/<abi>/`, and packaged into the APK. The app shells out to it.

So a full build has **two toolchains** that must agree on which ABIs to target:

1. **Go + Android NDK** → compiles restic → `app/src/main/jniLibs/<abi>/librestic.so`
   (driven by `scripts/build_restic.sh`, wrapped by the Gradle `buildBundledRestic` task).
2. **Gradle / AGP** → compiles the Kotlin app and packages the `.so` files into the APK.

If these two disagree on ABIs, you either build ABIs you don't ship (wasted time) or
ship an APK missing its native libs.

### Key files

| File | Role |
|---|---|
| `app/build.gradle.kts` | AGP config. Defines `enabledAbis` (see below), ABIs, splits, version codes. |
| `scripts/build_restic.sh` | Builds restic per ABI with the NDK toolchain. Honors `RESTIC_ABIS`. |
| `restic/` | Git submodule — the restic source (checked out with `submodules: recursive`). |
| `.github/workflows/android-debug.yml` | Debug CI: single arm64-v8a APK + rolling raw-apk release. |
| `.github/workflows/android-release.yml` | Release CI (tag-triggered): signed APKs + F-Droid repo. Builds **both** ABIs. |
| `.go-version` | Pinned Go version used to compile restic. |

## The ABI control system (added in this work)

ABIs are controlled from a **single Gradle property** with a matching env var for the
restic script. Defaults preserve the original behavior (both ABIs).

```kotlin
// app/build.gradle.kts
val enabledAbis = (providers.gradleProperty("enabledAbis").orNull
    ?: "arm64-v8a,x86_64")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
```

- `ndk { abiFilters.addAll(enabledAbis) }` — which native libs get packaged.
- `splits.abi.isEnable = enabledAbis.size > 1` — **only split when >1 ABI**. With a single
  ABI, splits are off and Gradle emits exactly **one** APK (`app-debug.apk`) instead of
  per-ABI + universal.
- `buildBundledRestic.onlyIf` checks only the enabled ABIs.

The restic script mirrors this via an env var (kept in sync manually):

```bash
RESTIC_ABIS="arm64-v8a" ./scripts/build_restic.sh
```

**Debug CI passes both**: `RESTIC_ABIS=arm64-v8a` (skip x86_64 Go compile — the biggest
time saver) **and** `./gradlew assembleDebug -PenabledAbis=arm64-v8a`. Both are required.

**Release CI builds both ABIs** (default) and is intentionally left untouched.

---

## ⚠️ Gotchas & lessons learned (read these — they cost real time)

### 1. `compression-level: 0` does NOT mean "no zip"

GitHub Actions **always** wraps `upload-artifact` outputs in a `.zip`. There is no option
to disable this. `compression-level: 0` only means the APK *inside* the zip is stored
uncompressed (APKs are already zips, so re-compressing is pointless). The artifact you
download from the Actions UI is therefore always `something.zip` containing the apk.

**If you need a raw `.apk` download link** (installable directly), publish it as a GitHub
**Release asset** — release assets are real files, no wrapper. The debug workflow does this
via a rolling prerelease tagged `debug` (`softprops/action-gh-release@v3`), overwritten on
every push so the link is stable. Verified working (~88 MB, mime
`application/vnd.android.package-archive`):
`https://github.com/GSathyaPrakash/restoid/releases/download/debug/restoid-arm64-debug.apk`

Don't waste time trying to make `upload-artifact` produce a raw apk — it can't.
That's why the debug workflow uses **no `upload-artifact` step at all**: the rolling
`debug` release is the sole output, so there is no `.zip` anywhere.

> **Do NOT re-add an `upload-artifact` step for the APK.** The repo owner has explicitly
> rejected the `.zip` wrapper it produces — the rolling release is the intended download
> mechanism. (If you ever add an artifact upload for a different reason, set
> `if-no-files-found: error` so a wrong path fails loudly instead of silently uploading nothing.)

### 2. The `@v7` / `@v6` action versions ARE valid — do not "fix" them by downgrading

This repo uses `actions/checkout@v7`, `actions/setup-java@v5`, `actions/setup-go@v7`,
`gradle/actions/setup-gradle@v6`, `android-actions/setup-android@v4`, `softprops/action-gh-release@v3`,
`peaceiris/actions-gh-pages@v4`. At the time of writing these are all **current/latest** and correct.
(`upload-artifact@v7` was also valid when it was in use; that step has since been removed — see gotcha #1.)
An earlier instinct was that `checkout@v7` looked "too high" and might be the debug-build bug — it was not.

**Always verify action versions against the GitHub API before changing them:**
```bash
curl -fsSL "https://api.github.com/repos/actions/upload-artifact/releases/latest" | grep tag_name
# For sub-actions like gradle/actions/setup-gradle, query the org repo:
curl -fsSL "https://api.github.com/repos/gradle/actions/releases/latest" | grep tag_name
```
Don't assume `@v4` is the ceiling because of older knowledge — these move fast.

### 3. Restricting ABIs means TWO places, not one

Telling Gradle to build arm64-only (`-PenabledAbis=arm64-v8a`) is not enough —
`build_restic.sh` would still compile x86_64 restic (slow). And setting only
`RESTIC_ABIS` is not enough — AGP would still try to package/split x86_64.
**Set both.** They must agree, or builds break.

### 4. Single ABI ⇒ disable ABI splits, or you get 2 APKs

With `splits.abi.isEnable = true` and one ABI, AGP still produces a per-ABI APK **and** a
universal APK (identical content) — i.e. two artifacts, not one. To get exactly one APK,
disable splits when only one ABI is selected (`isEnable = enabledAbis.size > 1`). The output
is then a single `app-debug.apk` (no ABI suffix in the name — note this for `path:` globs).

### 5. Push auth: the remote was HTTPS with no credential helper

`git push` over HTTPS failed with `could not read Username for 'https://github.com'`
(no credential helper, no `gh` CLI installed in the work env). Fix: switch the remote to SSH:
```bash
git remote set-url origin git@github.com:GSathyaPrakash/restoid.git
```
SSH keys (`~/.ssh/id_ed25519`) are already set up and authenticate as `GSathyaPrakash`.
Verify with `ssh -T git@github.com`. If a future push fails on auth, check the remote URL
first.

### 6. NDK version is not hardcoded in CI — it's parsed

Both workflows and `build_restic.sh` read `ndkVersion` from `app/build.gradle.kts`:
```bash
NDK_VER=$(sed -n -E 's/.*ndkVersion = "(.*)".*/\1/p' app/build.gradle.kts | tr -d ' ')
```
Currently `29.0.14206865`. **Don't hardcode it in CI** — change it once in
`build.gradle.kts` and everything follows. Same for `minSdk` (read by the restic script
to pick the right `android<API>` clang).

---

## CI behavior summary

### Debug (`android-debug.yml`) — triggers on push to `master`, PRs, and manual dispatch
- Builds restic **arm64-v8a only** (`RESTIC_ABIS=arm64-v8a`) → fast.
- `./gradlew assembleDebug -PenabledAbis=arm64-v8a` → one `app-debug.apk`.
- **No `upload-artifact` step** — it would always wrap the apk in a `.zip` (see gotcha #1).
- On **push** events only: publishes `restoid-arm64-debug.apk` as a raw asset to the rolling
  `debug` prerelease (see gotcha #1). PRs don't clobber it.
- Has `permissions: contents: write` (needed to create the release).

### Release (`android-release.yml`) — triggers on `v*.*.*` tags
- Builds **both** ABIs + universal APK, signed with repo secrets.
- Publishes a GitHub Release + maintains an F-Droid repo on GitHub Pages.
- Requires signing + F-Droid keystore secrets. **Leave ABI handling alone here.**

## Useful local commands

```bash
# Submodules first (restic source):
git submodule update --init --recursive

# Full debug build, both ABIs (matches default behavior):
./gradlew assembleDebug

# Fast single-ABI debug build (what CI does):
RESTIC_ABIS=arm64-v8a ./gradlew assembleDebug -PenabledAbis=arm64-v8a
# → app/build/outputs/apk/debug/app-debug.apk

# Build only the restic native binary(ies), arm64 only:
RESTIC_ABIS=arm64-v8a ./scripts/build_restic.sh
```

Requires: JDK 21, Go (`.go-version`), Android SDK + NDK (`29.0.14206865`),
`ANDROID_HOME`/`ANDROID_NDK_HOME` set. A Nix flake (`flake.nix`) is provided for dev shells.

## Conventions

- Keep `enabledAbis` (Gradle) and `RESTIC_ABIS` (script) in sync — both are documented
  inline and there's a comment cross-referencing them.
- Don't hardcode the NDK version or minSdk in CI/scripts — read them from `build.gradle.kts`.
- Commit messages: `ci(debug): ...`, `build: ...`, etc. (conventional-ish, matches existing history).
