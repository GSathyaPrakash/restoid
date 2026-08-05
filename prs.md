# Product Requirements Specification

## Title

Support Per-App Restic Repositories in Addition to the Current Single Repository Mode

## Background

Restoid currently stores backups for all selected applications inside a single Restic repository. This provides excellent cross-application deduplication and works well for complete device backups.

However, some users manage backups on a per-application basis. They frequently:

* Restore only a subset of apps after a factory reset.
* Add and remove applications over time.
* Permanently delete backups for applications they no longer use.
* Keep long backup histories only for selected apps.

With a single repository, deleting the complete history of one application is difficult because snapshots reference the entire backup set.

## Problem Statement

Users cannot easily manage backup history on a per-application basis.

Example workflow:

1. Back up 100 apps.
2. Factory reset device.
3. Restore only 10 apps.
4. Continue backing up only those 10 apps.
5. Decide WhatsApp is no longer needed.
6. Delete only the WhatsApp backup history.

Current repository architecture does not support this workflow cleanly.

## Goals

* Preserve existing single repository behavior.
* Add an optional per-app repository mode.
* Hide repository management from the user.
* Allow deleting an application's complete backup history with one action.
* Keep the user interface simple.

## Non-Goals

* Editing existing Restic snapshots.
* Migrating existing repositories automatically.
* Cross-repository deduplication.

## Proposed Solution

Add a new backup mode.

### Mode 1: Single Repository

Current behavior.

All selected applications are backed up into one Restic repository.

Advantages:

* Maximum deduplication
* Best for full-device backups
* Fully backward compatible

### Mode 2: Per-App Repository

Each application receives its own Restic repository.

Example:

Backups/

* com.whatsapp/
* org.telegram.messenger/
* org.mozilla.firefox/

Each directory contains an independent Restic repository.

Repository creation should happen automatically during the first backup.

## User Interface

Settings → Backup Mode

* Single Repository (Default)
* Per-App Repository

When Per-App mode is enabled:

Application list should display:

* Last backup
* Repository size
* Number of snapshots

Each application should expose actions such as:

* Backup
* Restore
* Delete Backup History

The user should never manually select repositories.

## Functional Requirements

### Backup

Single Repository:

* Existing behavior.

Per-App Repository:

* Create repository automatically if missing.
* Execute backup using the application's repository.

### Restore

Single Repository:

* Existing behavior.

Per-App Repository:

* Automatically open the application's repository.
* Restore selected snapshot.

### Delete Backup History

Per-App mode:

Deleting backup history should:

* Delete the application's repository.
* Remove associated metadata.
* Free storage immediately.

No interaction with other application repositories.

### Repository Metadata

Maintain lightweight metadata for each repository:

* Package name
* Display name
* Repository path
* Last backup timestamp
* Snapshot count
* Repository size

Metadata may be rebuilt by scanning repositories if necessary.

## Backward Compatibility

Existing users continue using Single Repository mode without changes.

Users may choose Per-App mode only for new backups.

Migration between modes is not required in the initial implementation.

## Benefits

* Better workflow for users performing partial restores.
* Easy deletion of obsolete application backups.
* Cleaner backup organization.
* Simpler long-term repository maintenance.

## Trade-offs

### Advantages

* Independent application lifecycle.
* Easier backup management.
* Immediate deletion of unwanted backup history.
* Better suited for frequently changing app collections.

### Disadvantages

* Loss of cross-application deduplication.
* More repositories on disk.
* Slight increase in storage usage.
* Slightly longer total backup time due to multiple Restic invocations.

## Future Enhancements

* Batch backup of multiple repositories.
* Repository health monitoring.
* Automatic repository compaction.
* Export/import individual application repositories.
* Optional grouping (e.g., "Games", "Work Apps") with one repository per group.

## Acceptance Criteria

* Existing users experience no behavior changes.
* Users can select Single Repository or Per-App Repository.
* New repositories are created automatically.
* Backup and restore work transparently in both modes.
* Users can delete one application's complete backup history without affecting any other application.
* Repository management remains hidden from the user.
