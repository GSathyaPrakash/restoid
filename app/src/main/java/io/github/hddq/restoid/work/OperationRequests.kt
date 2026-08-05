package io.github.hddq.restoid.work

import kotlinx.serialization.Serializable

@Serializable
data class BackupTypeSelection(
    val apk: Boolean = true,
    val data: Boolean = true,
    val deviceProtectedData: Boolean = true,
    val externalData: Boolean = false,
    val obb: Boolean = false,
    val media: Boolean = false,
    val permissions: Boolean = true
)

@Serializable
data class RestoreTypeSelection(
    val apk: Boolean = true,
    val data: Boolean = true,
    val deviceProtectedData: Boolean = true,
    val externalData: Boolean = false,
    val obb: Boolean = false,
    val media: Boolean = false,
    val permissions: Boolean = true
)

@Serializable
data class RestoreAppSelection(
    val packageName: String,
    val appName: String
)

@Serializable
data class BackupWorkRequest(
    val repositoryKey: String,
    val backupTypes: BackupTypeSelection,
    val selectedPackageNames: List<String>,
    val appBackupTypes: Map<String, BackupTypeSelection> = emptyMap(),
    val customDirectories: List<String> = emptyList(),
    /** When true, each selected app/dir is backed up into its own nested repository. */
    val perAppMode: Boolean = false
)

@Serializable
data class RestoreWorkRequest(
    val repositoryKey: String,
    val snapshotId: String,
    val restoreTypes: RestoreTypeSelection,
    val allowDowngrade: Boolean,
    val selectedApps: List<RestoreAppSelection>,
    val appRestoreTypes: Map<String, RestoreTypeSelection> = emptyMap(),
    val selectedCustomDirectories: List<String> = emptyList(),
    /** When set, credentials/env/options/backend come from this base repo while restoring
     * from [repositoryKey] (a per-app derived path). Null in single-repository mode. */
    val baseRepositoryKey: String? = null
)

@Serializable
data class MaintenanceWorkRequest(
    val repositoryKey: String,
    val checkRepo: Boolean,
    val pruneRepo: Boolean,
    val unlockRepo: Boolean,
    val readData: Boolean,
    val forgetSnapshots: Boolean,
    val keepLast: Int,
    val keepDaily: Int,
    val keepWeekly: Int,
    val keepMonthly: Int
)

@Serializable
data class RunTasksConfig(
    val backupEnabled: Boolean,
    val backupTypes: BackupTypeSelection,
    val selectedPackageNames: List<String>,
    val appBackupTypes: Map<String, BackupTypeSelection> = emptyMap(),
    val customDirectories: List<String> = emptyList(),
    val unlockRepo: Boolean,
    val forgetSnapshots: Boolean,
    val pruneRepo: Boolean,
    val checkRepo: Boolean,
    val readData: Boolean,
    val keepLast: Int,
    val keepDaily: Int,
    val keepWeekly: Int,
    val keepMonthly: Int
)

@Serializable
data class RunTasksWorkRequest(
    val repositoryKey: String,
    val backupEnabled: Boolean,
    val backupTypes: BackupTypeSelection,
    val selectedPackageNames: List<String>,
    val appBackupTypes: Map<String, BackupTypeSelection> = emptyMap(),
    val customDirectories: List<String> = emptyList(),
    val unlockRepo: Boolean,
    val forgetSnapshots: Boolean,
    val pruneRepo: Boolean,
    val checkRepo: Boolean,
    val readData: Boolean,
    val keepLast: Int,
    val keepDaily: Int,
    val keepWeekly: Int,
    val keepMonthly: Int,
    val perAppMode: Boolean = false,
    val scheduleName: String? = null
)
