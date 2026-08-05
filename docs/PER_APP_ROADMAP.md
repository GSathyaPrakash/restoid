# Restoid — Per-App Mode & Product Roadmap

> **Handoff document.** Read alongside [`AGENTS.md`](../AGENTS.md) (build/CI/native-build
> gotchas) and [`prs.md`](../prs.md) (original per-app spec). This doc captures (1) what is
> already implemented, (2) the evolved product direction, and (3) the pending work.
>
> **Strategic decision (settled):** stay on Restoid — do **not** fork DataBackup. Restic
> integration (native binary, multi-backend, dedup, encryption, the NDK/Go build pipeline) is
> the hard half and is already done here; DataBackup has none of it (its storage layer is
> tar+zstd). The DataBackup-style UX we want is a small UI delta on top of Restoid's working
> restic layer, not a reason to fork. See §5.

---

## 1. Two backup modes (current architecture)

Restoid has **one selected repository** (`RepositoriesRepository.selectedRepository`, keyed by
repo path). Everything resolves credentials/env/options from it.

- **Single Repository mode** (default, legacy): all selected apps + custom dirs back up into
  one shared restic repo. Max dedup. Implemented by `BackupOperationRunner`.
- **Per-App Repository mode** (new, opt-in): each app and each custom directory gets its own
  restic repo nested under the selected repo at `<selectedRepoPath>/<slug>`. Each item's
  history is independently deletable. Implemented by `PerAppBackupOperationRunner`.

Mode is a single global preference: `BackupMode { SINGLE, PER_APP }` in
`PreferencesRepository`, exposed as a `StateFlow` and toggled in **Settings → Options**.

---

## 2. What's DONE (per-app mode, on `master`)

All shipped, CI-green, additive (single mode untouched). Commits `5c33d59 → 3b7f12f`.

### 2.1 Data / model layer
| File | What |
|---|---|
| `data/PreferencesRepository.kt` | `BackupMode` enum; `saveBackupMode`/`loadBackupMode`; `backupMode: StateFlow<BackupMode>` |
| `data/PerAppItem.kt` | `PerAppItem` sealed type (`App(packageName)` / `CustomDir(uri)`); `PerAppRepositoryResolver.deriveRepoPath(base, slug)` — uniform `basePath + "/" + slug` works for LOCAL/SFTP/REST/S3 |
| `data/PerAppItemRegistry.kt` | `PerAppItemDescriptor`, `PerAppItemKind`; file-backed registry of per-app items keyed by **hash(base repo path)**. `getItems / addItem / removeItem`. Pure file I/O → safe to instantiate from any `Context` |
| `data/ResticRepository.kt` | `ensureRepository()` (init-if-missing via `cat config` probe); `forgetAll()` (forget all snapshots + `--prune` → deletes history, frees storage, works on every backend) |

### 2.2 Backup path
| File | What |
|---|---|
| `work/PerAppBackupOperationRunner.kt` | Per-item backup loop. Shares base repo's **password, credentials, env, restic options**; lazily `init`s each child repo; backs up each item; **registers** it in `PerAppItemRegistry`; saves per-snapshot metadata under the **derived** repo id. Progress = `(i + frac)/N` |
| `work/OperationRequests.kt` | `BackupWorkRequest.perAppMode`, `RunTasksWorkRequest.perAppMode`, `RestoreWorkRequest.baseRepositoryKey` |
| `work/RunTasksOperationRunner.kt` | Backup phase branches on `perAppMode` (`perAppBackupRunner` vs `backupRunner`) |
| `work/HeavyOperationWorker.kt` | BACKUP op branches on `perAppMode`; RUN_TASKS passes `metadataRepository` |
| `ui/runtasks/RunTasksViewModel.kt`, `work/ScheduleWorker.kt` | Set `perAppMode = loadBackupMode() == PER_APP` (covers manual runs **and** schedules) |

### 2.3 Restore path
| File | What |
|---|---|
| `work/RestoreOperationRunner.kt` | Optional `baseRepositoryKey`: when set, credentials/env/options/backend come from base while restoring from the derived path. **Single mode is byte-for-byte unchanged** (`credKey == repositoryKey` when null) |

### 2.4 UI
| File | What |
|---|---|
| `ui/screens/settings/OptionsSettings.kt` + `components/SettingsComponents.kt` (`PerAppModeRow`) + `ui/settings/SettingsViewModel.kt` | "Per-App Repository Mode" toggle (off by default) |
| `ui/home/PerAppHomeViewModel.kt` (+ factory) | Per-app Home state: lists registry items w/ snapshot count + last backup; **delete history** (`forgetAll` + registry remove); **restore** (loads item snapshots, latest pre-selected, enqueues `RestoreWorkRequest` w/ `baseRepositoryKey`, emits nav event) |
| `ui/screens/PerAppHomeScreen.kt` | Per-app Home UI: pull-to-refresh, empty/error states, per-row Restore (apps w/ snapshots) + Delete, snapshot-picker dialog |
| `MainActivity.kt` | Branches Home on `backupMode`; collects per-app nav events → OperationProgress |

### 2.5 How a user exercises it today
1. Settings → Options → enable **Per-App Repository Mode**.
2. **Tasks** → select apps → Run. Each app/dir creates its own nested repo.
3. **Home** → per-app list (refresh after a backup) → **Restore** (pick snapshot) / **Delete** history.

---

## 3. Known limitations / gotchas (v1)

- **Permission restore is skipped in per-app mode.** Per-snapshot metadata is keyed by the
  *derived* repo id, which `RestoreOperationRunner` doesn't resolve yet. APK + data restore
  work fully. **Fix:** resolve derived id via `getConfig` in the runner, or read `restoid.json`
  from the restored tree.
- **No cross-repo metadata sync.** Single mode mirrors `restoid.json` into the repo so it
  survives reinstall; per-app mode does not (would mean N metadata snapshots). Acceptable.
- **Per-app item registry is device-local.** A fresh install loses the Home list; for remote
  backends there's no way to rediscover child repos (restic can't list repos). **Fix (LOCAL
  only):** scan `<base>/*/config` to rebuild the registry. Confirmed empirically: child repos
  are plain dirs each with a `config` file.
- **Per-app Home does not auto-refresh on resume.** It loads on init + manual pull-to-refresh.
  After a backup, the user must pull-to-refresh. **Fix:** reload on lifecycle resume.
- **Per-app Home shows only already-backed-up apps** (from the registry), not all installed
  apps. The new vision (§5) changes this.

---

## 4. Architecture cheat-sheet (for the next session)

- **Selected repo** = `RepositoriesRepository.selectedRepository.value` (String? = repo path).
  All credential lookups (`getRepositoryPassword`, `getExecutionEnvironmentVariables`,
  `getExecutionResticOptions`, `hasSftpCredentials`, …) are keyed by it.
- **Per-app derived repo** = `PerAppRepositoryResolver.deriveRepoPath(baseRepo, slug)` ≡
  `baseRepo.path + "/" + slug`. Slug: app → sanitized package name; custom dir →
  `<sanitized name>_<sha256(uri)[:8]>`.
- **Per-app credential reuse:** child repos inherit the base repo's password + SFTP/REST/S3
  creds + env + restic options verbatim (SFTP known-hosts/key paths are absolute app-internal
  paths, so they're path-independent). No per-child credential plumbing exists or is needed.
- **Operation dispatch:** UI ViewModel → `OperationWorkRepository.enqueueBackup/RunTasks/Restore`
  → `HeavyOperationWorker` → runner. `RunTasksOperationRunner` is a composite (backup +
  maintenance). Requests are serialized to `filesDir/operation_requests/*.json` (transient).
- **Schedules:** `ui/schedules/*`, `model/Schedule.kt`; stored per-repo under
  `filesDir/metadata/<repoId>/schedules.json` (`MetadataRepository`). `ScheduleWorker` builds a
  `RunTasksWorkRequest` and enqueues it.
- **Reusable UI bits:** `ui/shared/TaskConfigComponents.kt` → `AppListItem` (per-app toggle),
  `BackupTypesBottomSheet` (apk/data/deviceProtectedData/externalData/obb/media/permissions,
  per-app **and** bulk), `BackupTypesConfigCard` (select-all + per-app type config).
- **Backup type model:** `work/OperationRequests.kt` → `BackupTypeSelection` /
  `RestoreTypeSelection`; `ui/shared/BackupTypes.kt` → `BackupTypes.toSelection()`; restore
  analog in `ui/restore/RestoreViewModel.kt` → `RestoreTypes.toSelection()`.
- **Custom dirs today:** `model/CustomDirectory.kt` (just `{uri, isSelected}`); persisted as
  two StringSets in `PreferencesRepository` (`loadCustomDirectoriesAll/Selected`); resolved via
  `util/StorageUtils.getPathFromTreeUri` + `resolvePathForShell`.

---

## 5. New product direction (DataBackup-inspired)

Goal: a tag-driven, schedule-oriented, app-centric backup tool — Restoid's restic backend +
DataBackup's organizing UX. Applies primarily to **per-app mode** but the selection/search/tags
benefit both modes.

### 5.1 Tags → "Backup Profiles"

A **BackupProfile** is a named preset of backup types. Apps are assigned a profile. Schedules
target profiles.

```kotlin
@Serializable
data class BackupProfile(
    val id: String,                 // uuid
    val name: String,               // "APK", "APK + Data", "Full"
    val types: BackupTypeSelection  // which parts this profile backs up
)
```

- Seed defaults: `APK` (apk), `Data` (data+deviceProtectedData), `APK + Data`, `Full`
  (apk+data+deviceProtectedData+externalData+obb+media), `Data + Media`, etc. User-defined too.
- **App → profile:** `Map<packageName, profileId>` (persisted; default = designated default
  profile). This replaces per-app ad-hoc type config as the *source of truth* (ad-hoc override
  still allowed in Tasks).
- UI: in the app list, each app row shows its profile (tappable → picker). Bulk reassign by
  multi-select → "Assign profile".

### 5.2 Schedules (tag/profile-driven)

A schedule selects **profiles** (→ all apps with those profiles, each backed up per its profile
types) + **custom folders** + recurrence + base repo + mode.

```kotlin
@Serializable
data class Schedule(
    val id, name, isEnabled: …,
    val recurrence: …,              // time/cron (existing model has time fields)
    val profileIds: List<String>,   // apps whose profile ∈ this set are included
    val customFolderIds: List<String>,
    val repositoryKey: String,      // base repo
    val backupMode: BackupMode,
    val lastRunTimestamp: Long
)
```

- Example: schedule "Nightly APKs" → profile `APK` → daily 03:00 → backs up only the APK of
  every app tagged `APK`.
- Maintenance is **not** part of schedules anymore (moved to Settings, §5.4). A schedule may
  optionally tick "run default maintenance after".
- Extends the existing `model/Schedule.kt` + `ScheduleWorker` (which already builds a
  `RunTasksWorkRequest`). New: resolve app set from `profileIds`, set per-app types from each
  app's profile.

### 5.3 Redesigned "Tasks" = ad-hoc run

Current Tasks crams three concerns (app selection + custom-folder config + maintenance). The
redesign **splits them**:

- **Maintenance → Settings** (§5.4).
- **Custom folders → first-class managed items** (§5.5), not a Tasks sub-section.
- **Tasks becomes a pure ad-hoc backup runner:** `search + multi-select apps → preview what
  will back up (per-app parts, derived from each app's profile) → Run`. Updates snapshots.
  Types come from profiles by default; inline override allowed per the preview.

UX: search bar at top, multi-select checkboxes, a "Run" action that shows a preview sheet
("12 apps · APK only, 3 apps · Full, 2 folders") then enqueues. No maintenance toggles, no
folder-add here.

### 5.4 Maintenance → Settings

Move prune / check (read-data) / forget / unlock / **metadata validate** out of Tasks into a
dedicated **Settings → Maintenance** section (per selected repo). Reuses
`MaintenanceOperationRunner` + `MaintenanceWorkRequest` as-is; only the entry UI moves.

### 5.5 Custom folders as first-class targets

Treat folders like apps: each is a managed item with a name, a history (snapshots), and
restore/delete. In per-app mode each already gets its own repo (`PerAppItem.CustomDir`).

**Recommended add/remove UX (decision needed — see §7):**
- **Add:** a `+` action in the Home **top app bar** → opens the system **SAF directory picker**
  → chosen tree URI becomes a persisted `CustomFolder` (stable id + display name) and joins the
  per-app Home list. (Matches your instinct and keeps everything app-like.)
- **Manage/rename/remove:** long-press or a per-row menu on the folder row, or a "Folders"
  screen reachable from Settings.

```kotlin
@Serializable
data class CustomFolder(
    val id: String,            // uuid, stable
    val uri: String,           // SAF tree URI
    val displayName: String    // last path segment or user-set
)
```

This supersedes the current `CustomDirectory {uri, isSelected}` + two-StringSet prefs model
with a proper persisted list (`CustomFolderStore`). Schedules reference folders by id.

---

## 6. Roadmap / pending work (suggested phases)

Each phase = one checkpoint (commit + push; debug CI builds the arm64 APK and publishes to the
rolling `debug` release — see AGENTS.md).

**Phase A — Per-app Home: app selection surface (the immediate ask)**
- Per-app Home lists **all installed apps** (from `AppInfoRepository`), not just registry items.
- Multi-select **checkboxes** + **search bar**.
- Merge backup status (last backup/count from registry) onto rows; keep Restore/Delete for
  backed-up apps.
- "Backup selected" FAB → enqueues per-app backup (`RunTasksWorkRequest` w/ `perAppMode=true`,
  default types).
- Reuse `BackupTypesBottomSheet` for per-app + bulk type config.
- Auto-refresh on resume (fixes the stale-list gotcha).

**Phase B — Backup Profiles (tags)**
- `BackupProfile` model + store + seeded defaults.
- App → profile assignment (persisted map); UI to assign per-app and bulk.
- Tasks/Home app rows show profile; schedules and ad-hoc runs derive types from profiles.

**Phase C — Schedules v2 (profile-driven)**
- Extend `Schedule` with `profileIds` + `customFolderIds`; resolve app set at run time.
- Schedule UI: pick profiles + folders + recurrence + repo + mode.

**Phase D — Tasks redesign + Maintenance relocation**
- Strip Tasks to ad-hoc run: search + multi-select + preview + Run.
- New Settings → Maintenance section (prune/check/forget/unlock/metadata-validate).

**Phase E — Custom folders first-class**
- `CustomFolder` model + `CustomFolderStore`; `+` on Home top bar (SAF picker).
- Folders appear in per-app Home list + selectable in schedules.
- Per-folder restore/delete.

**Phase F — Hardening / parity gaps**
- Per-app permission restore (resolve derived repo id).
- LOCAL registry rebuild-by-scan (reinstall resilience).
- App icons on the per-app Home rows.
- (Optional) profile/groups grouping ("Games", "Work").

---

## 7. Open decisions (need your call)

1. **Custom-folder add UX:** `+` in Home top app bar (recommended) vs. a dedicated "Folders"
   screen vs. inside Settings. → *Recommend the `+` on Home top bar; folders live in the same
   list as apps.*
2. **Profile assignment cardinality:** one profile per app (simple, recommend) vs. many tags per
   app (DataBackup-style filtering). → *Recommend one profile per app; "tags as filters" can be
   a later layer if needed.*
3. **Single mode scope of the new UX:** build the new app-selection/profiles for both modes, or
   per-app only? → *Recommend both (search/multi-select/profiles are mode-agnostic); schedules
   already carry `backupMode`.*
4. **Maintenance automation:** keep a per-schedule "run maintenance after" toggle, or fully
   manual from Settings? → *Recommend optional toggle off by default.*

---

## 8. Build / CI / device notes

- **Build:** `RESTIC_ABIS=arm64-v8a ./gradlew assembleDebug -PenabledAbis=arm64-v8a` →
  `app/build/outputs/apk/debug/app-debug.apk`. Requires JDK 21, Go (`.go-version`), Android
  SDK + NDK `29.0.14206865`. A Nix flake dev shell is provided. See AGENTS.md.
- **No local toolchain in the agent env** (no JDK/SDK) — the agent cannot compile; CI is the
  compile gate. Push at every checkpoint.
- **Debug CI** (`.github/workflows/android-debug.yml`): arm64-v8a only; publishes a raw APK to
  the rolling `debug` prerelease:
  `https://github.com/GSathyaPrakash/restoid/releases/download/debug/restoid-arm64-debug.apk`
- **Debug package id:** `io.github.hddq.restoid.debug` (`applicationIdSuffix = ".debug"`).
- **Debugging on device (HyperOS/MIUI):**
  - `adb shell run-as io.github.hddq.restoid.debug …` is **blocked by SELinux** on this device.
  - There is **no `su` for the adb shell** (root is granted to *apps* via a root manager, not
    the shell uid). To read app-private files, grant **Shell** superuser in the root manager.
  - The adb shell **can** read shared storage — useful for inspecting LOCAL repos
    (`/storage/emulated/0/<repo>/…`). A restic repo = a dir with `config` + `data/keys/
    snapshots/index/`. Per-app child repos are subdirs each with their own `config`.
  - **Per-app registry location:** `files/per_app_items/<sha256(baseRepoPath)[:…]>.json`
    (app-private). Empty registry + existing child repos ⇒ backups predated the registry code,
    or selected repo ≠ the one backed up.
