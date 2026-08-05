package io.github.hddq.restoid.work

import android.content.Context
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import io.github.hddq.restoid.R
import io.github.hddq.restoid.data.AppInfoRepository
import io.github.hddq.restoid.data.MetadataRepository
import io.github.hddq.restoid.data.OperationLockManager
import io.github.hddq.restoid.data.PerAppItem
import io.github.hddq.restoid.data.PerAppRepositoryResolver
import io.github.hddq.restoid.data.RepositoriesRepository
import io.github.hddq.restoid.data.ResticBinaryManager
import io.github.hddq.restoid.data.ResticRepository
import io.github.hddq.restoid.data.ResticState
import io.github.hddq.restoid.model.AppInfo
import io.github.hddq.restoid.model.AppMetadata
import io.github.hddq.restoid.model.CustomDirectoryMetadata
import io.github.hddq.restoid.model.RestoidMetadata
import io.github.hddq.restoid.ui.shared.OperationProgress
import io.github.hddq.restoid.util.ResticOutputParser
import io.github.hddq.restoid.util.StorageUtils
import io.github.hddq.restoid.util.buildResticOptionFlags
import io.github.hddq.restoid.util.buildShellEnvironmentPrefix
import io.github.hddq.restoid.util.shellQuote
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-app repository backup runner.
 *
 * Unlike [BackupOperationRunner] (which writes every selected app into one shared
 * repository), this runner backs up each application and each custom directory
 * into its own nested restic repository under the selected (base) repository.
 * Each nested repository is created automatically on first use and shares the
 * base repository's password, credentials, environment and restic options.
 *
 * Overall progress is reported on a [0,1] scale as
 * (itemIndex + itemFraction) / itemCount, so it composes correctly both
 * standalone and when nested inside RunTasks via progress remapping.
 *
 * NOTE: v1 intentionally does not replicate the cross-repository metadata
 * backup/restore dance that single-repository mode uses. Each per-app repo is
 * self-contained; per-snapshot metadata is still persisted locally so the UI
 * can show it, but it is not mirrored into the per-app repos.
 */
class PerAppBackupOperationRunner(
    private val context: Context,
    private val repositoriesRepository: RepositoriesRepository,
    private val resticBinaryManager: ResticBinaryManager,
    private val resticRepository: ResticRepository,
    private val appInfoRepository: AppInfoRepository,
    private val metadataRepository: MetadataRepository,
    private val operationLockManager: OperationLockManager
) {

    private val json = Json { prettyPrint = true }

    suspend fun run(
        request: BackupWorkRequest,
        onProgress: (OperationProgress) -> Unit,
        shouldStop: () -> Boolean = { false },
        @Suppress("UNUSED_PARAMETER")
        stageContext: OperationStageContext = OperationStageContext(
            completedStagesBefore = 0,
            totalStages = 3
        )
    ): OperationRunResult {
        fun throwIfCancelled() {
            if (shouldStop()) throw OperationCancelledException(context.getString(R.string.operation_interrupted))
        }

        val startTime = System.currentTimeMillis()
        var progressState = OperationProgress(stageTitle = context.getString(R.string.progress_initializing))
        onProgress(progressState)

        var operationLockAcquired = false
        var isSuccess = true
        val summaries = mutableListOf<String>()

        try {
            throwIfCancelled()

            val baseKey = request.repositoryKey
            val baseRepository = repositoriesRepository.getRepositoryByKey(baseKey)
                ?: return OperationRunResult(
                    success = false,
                    progress = OperationProgress(
                        isFinished = true,
                        error = context.getString(R.string.error_no_backup_repository_selected),
                        finalSummary = context.getString(R.string.summary_no_backup_repository_selected)
                    )
                )

            if (resticBinaryManager.resticState.value !is ResticState.Installed) {
                return OperationRunResult(
                    success = false,
                    progress = OperationProgress(
                        isFinished = true,
                        error = context.getString(R.string.error_restic_not_installed),
                        finalSummary = context.getString(R.string.summary_restic_binary_not_installed)
                    )
                )
            }

            val password = repositoriesRepository.getRepositoryPassword(baseKey)
                ?: return OperationRunResult(
                    success = false,
                    progress = OperationProgress(
                        isFinished = true,
                        error = context.getString(R.string.error_password_not_found_for_repository),
                        finalSummary = context.getString(R.string.summary_password_not_found)
                    )
                )

            operationLockManager.acquire(baseRepository.backendType)
            operationLockAcquired = true

            val resticState = resticBinaryManager.resticState.value as ResticState.Installed
            val baseEnv = repositoriesRepository.getExecutionEnvironmentVariables(baseKey)
            val baseOptions = repositoriesRepository.getExecutionResticOptions(baseKey)

            val plans = buildPlans(request)
            if (plans.isEmpty()) {
                return OperationRunResult(
                    success = false,
                    progress = OperationProgress(
                        isFinished = true,
                        error = context.getString(R.string.per_app_error_no_items),
                        finalSummary = context.getString(R.string.per_app_error_no_items)
                    )
                )
            }

            val itemCount = plans.size
            var passwordFile: File? = null
            var fileList: File? = null
            var metadataTempFile: File? = null

            try {
                passwordFile = File.createTempFile("restic-pass", ".tmp", context.cacheDir)
                passwordFile.writeText(password)

                plans.forEachIndexed { index, plan ->
                    throwIfCancelled()
                    val item = plan.item
                    val derivedPath = PerAppRepositoryResolver.deriveRepoPath(baseRepository, item)

                    progressState = progressState.copy(
                        stageTitle = context.getString(
                            R.string.per_app_stage_preparing_item,
                            item.displayName,
                            index + 1,
                            itemCount
                        ),
                        stagePercentage = 0f,
                        overallPercentage = index.toFloat() / itemCount,
                        elapsedTime = (System.currentTimeMillis() - startTime) / 1000,
                        isFinished = false
                    )
                    onProgress(progressState)
                    throwIfCancelled()

                    // Lazily create the nested repository if it does not exist yet.
                    val ensureResult = resticRepository.ensureRepository(derivedPath, password, baseEnv, baseOptions)
                    if (ensureResult.isFailure) {
                        val cause = ensureResult.exceptionOrNull()?.message.orEmpty()
                        summaries += context.getString(R.string.per_app_error_init_failed, item.displayName, cause)
                        isSuccess = false
                        return@forEachIndexed
                    }

                    val derivedRepoId = resticRepository.getConfig(derivedPath, password, baseEnv, baseOptions)
                        .getOrNull()?.id

                    // Write per-item restoid.json metadata and include it in the backup,
                    // mirroring BackupOperationRunner.
                    val metadata = RestoidMetadata(
                        apps = plan.appMetadata?.let { mapOf(it) } ?: emptyMap(),
                        customDirectories = plan.customDirMetadata ?: emptyMap()
                    )
                    metadataTempFile = File(context.cacheDir, "restoid.json")
                    metadataTempFile!!.writeText(json.encodeToString(metadata))

                    val pathsToBackup = (plan.paths + metadataTempFile.absolutePath).distinct()
                    val hasOnlyMetadataFile = pathsToBackup.size <= 1 &&
                        plan.appMetadata == null &&
                        plan.customDirMetadata == null
                    if (hasOnlyMetadataFile) {
                        summaries += context.getString(R.string.backup_error_no_files_selected_for_items)
                        isSuccess = false
                        metadataTempFile?.delete()
                        metadataTempFile = null
                        return@forEachIndexed
                    }

                    fileList = File.createTempFile("restic-files-", ".txt", context.cacheDir)
                    fileList!!.writeText(pathsToBackup.joinToString("\n"))

                    val stageTitle = context.getString(
                        R.string.per_app_stage_backing_up,
                        item.displayName,
                        index + 1,
                        itemCount
                    )

                    val tagFlags = listOf("restoid", "backup").joinToString(" ") { "--tag '$it'" }
                    val excludeFlags = plan.excludePatterns.distinct().joinToString(" ") { p -> "--exclude $p" }
                    val envPrefix = buildShellEnvironmentPrefix(baseEnv)
                    val optionFlags = buildResticOptionFlags(baseOptions)

                    val command = buildString {
                        if (envPrefix.isNotEmpty()) append(envPrefix).append(' ')
                        append("RESTIC_PASSWORD_FILE=").append(shellQuote(passwordFile!!.absolutePath)).append(' ')
                        append("RESTIC_CACHE_DIR=").append(shellQuote(File(context.cacheDir, if (Shell.getShell().isRoot) "restic" else "restic-user").absolutePath)).append(' ')
                        append(shellQuote(resticState.path)).append(' ')
                        append("--retry-lock 5s ")
                        if (optionFlags.isNotEmpty()) append(optionFlags).append(' ')
                        append("-r ").append(shellQuote(derivedPath)).append(' ')
                        append("backup --files-from ").append(shellQuote(fileList!!.absolutePath))
                        append(" --json --verbose=2 ")
                        append(tagFlags)
                        append(' ')
                        append(excludeFlags)
                    }

                    var snapshotId: String? = null
                    var itemFinalSummary: String? = null
                    val errorMessages = mutableListOf<String>()
                    var isOnlySafeErrors = true
                    val stderr = mutableListOf<String>()

                    val stdoutCallback = object : CallbackList<String>() {
                        override fun onAddElement(line: String) {
                            try {
                                val lineJson = org.json.JSONObject(line)
                                if (lineJson.optString("message_type") == "error") {
                                    val itemField = lineJson.optString("item")
                                    val message = lineJson.optJSONObject("error")?.optString("message") ?: ""
                                    val isSafe = itemField == "/data/user/0" && message.contains("user.serial")
                                    if (!isSafe) {
                                        isOnlySafeErrors = false
                                        errorMessages.add("Error in $itemField: $message")
                                    }
                                }
                            } catch (_: Exception) {
                            }

                            ResticOutputParser.parse(line, context)?.let { update ->
                                if (update.isFinished) {
                                    snapshotId = update.snapshotId
                                    itemFinalSummary = update.finalSummary
                                }
                                progressState = update.copy(
                                    isFinished = false,
                                    stageTitle = stageTitle,
                                    overallPercentage = (index + update.stagePercentage.coerceIn(0f, 1f)) / itemCount,
                                    elapsedTime = (System.currentTimeMillis() - startTime) / 1000
                                )
                                onProgress(progressState)
                            }
                        }
                    }

                    val result = Shell.cmd(command).to(stdoutCallback, stderr).exec()
                    throwIfCancelled()

                    val isResticSuccess = result.isSuccess || (result.code == 3 && isOnlySafeErrors)
                    if (!isResticSuccess || snapshotId == null) {
                        val errorOutput = if (errorMessages.isNotEmpty()) {
                            errorMessages.joinToString("\n")
                        } else {
                            stderr.joinToString("\n")
                        }
                        val msg = if (errorOutput.isEmpty()) {
                            context.getString(R.string.backup_error_command_failed_with_code, result.code)
                        } else {
                            errorOutput
                        }
                        summaries += context.getString(R.string.per_app_error_backup_failed, item.displayName, msg)
                        isSuccess = false
                    } else {
                        if (derivedRepoId != null) {
                            runCatching {
                                metadataRepository.saveMetadataForSnapshot(derivedRepoId, snapshotId!!, metadata)
                            }
                        }
                        summaries += context.getString(
                            R.string.run_tasks_phase_summary,
                            item.displayName,
                            itemFinalSummary ?: context.getString(R.string.operation_backup)
                        )
                    }

                    fileList?.delete(); fileList = null
                    metadataTempFile?.delete(); metadataTempFile = null
                }
            } finally {
                fileList?.delete()
                passwordFile?.delete()
                metadataTempFile?.delete()
            }

            progressState = progressState.copy(
                stageTitle = if (isSuccess) {
                    context.getString(R.string.progress_operation_complete, context.getString(R.string.operation_backup))
                } else {
                    context.getString(R.string.progress_operation_failed, context.getString(R.string.operation_backup))
                },
                stagePercentage = 1f,
                overallPercentage = 1f,
                elapsedTime = (System.currentTimeMillis() - startTime) / 1000,
                isFinished = true,
                error = if (!isSuccess) {
                    summaries.joinToString("\n").ifBlank { context.getString(R.string.run_tasks_error_one_or_more_failed) }
                } else {
                    null
                },
                finalSummary = summaries.joinToString("\n\n").ifBlank {
                    context.resources.getQuantityString(R.plurals.backup_summary_success_items, itemCount, itemCount)
                }
            )
        } catch (e: OperationCancelledException) {
            isSuccess = false
            progressState = OperationProgress(
                stageTitle = context.getString(R.string.progress_operation_failed, context.getString(R.string.operation_backup)),
                isFinished = true,
                error = context.getString(R.string.operation_interrupted),
                finalSummary = context.getString(R.string.operation_interrupted),
                elapsedTime = (System.currentTimeMillis() - startTime) / 1000
            )
        } catch (e: Exception) {
            isSuccess = false
            val msg = context.getString(R.string.error_fatal_with_message, e.message ?: "")
            progressState = OperationProgress(
                stageTitle = context.getString(R.string.progress_operation_failed, context.getString(R.string.operation_backup)),
                isFinished = true,
                error = msg,
                finalSummary = msg,
                elapsedTime = (System.currentTimeMillis() - startTime) / 1000
            )
        } finally {
            if (operationLockAcquired) {
                operationLockManager.release()
            }
        }

        return OperationRunResult(success = isSuccess, progress = progressState)
    }

    /** Builds the per-item plan list: apps first (in selection order), then custom directories. */
    private suspend fun buildPlans(request: BackupWorkRequest): List<ItemPlan> {
        val plans = mutableListOf<ItemPlan>()
        val apps = appInfoRepository.getAppInfoForPackages(request.selectedPackageNames)

        apps.forEach { app ->
            val effective = request.appBackupTypes[app.packageName] ?: request.backupTypes
            val paths = mutableListOf<String>()
            val excludes = mutableListOf<String>()

            generateFilePathsForApp(app, effective).forEach { p ->
                if (Shell.cmd("[ -e '$p' ]").exec().isSuccess) paths.add(p)
            }
            if (effective.data) {
                excludes.add("'/data/user/$currentUserId/${app.packageName}/cache'")
                excludes.add("'/data/user/$currentUserId/${app.packageName}/code_cache'")
            }
            if (effective.externalData) {
                excludes.add("'/storage/emulated/$currentUserId/Android/data/${app.packageName}/cache'")
            }

            val typesList = mutableListOf<String>().apply {
                if (effective.apk) add("apk")
                if (effective.data) add("data")
                if (effective.deviceProtectedData) add("user_de")
                if (effective.externalData) add("external_data")
                if (effective.obb) add("obb")
                if (effective.media) add("media")
                if (effective.permissions) add("permissions")
            }
            val granted = if (effective.permissions) {
                appInfoRepository.getGrantedRuntimePermissions(app.packageName)
            } else {
                emptyList()
            }

            plans.add(
                ItemPlan(
                    item = PerAppItem.App(app.packageName),
                    paths = paths,
                    excludePatterns = excludes,
                    appMetadata = app.packageName to AppMetadata(
                        size = getDirectorySize(paths),
                        types = typesList,
                        versionCode = app.versionCode,
                        versionName = app.versionName,
                        appName = app.name,
                        grantedRuntimePermissions = granted
                    ),
                    customDirMetadata = null
                )
            )
        }

        request.customDirectories.forEach { uriStr ->
            val uri = android.net.Uri.parse(uriStr)
            val realPath = StorageUtils.getPathFromTreeUri(uri) ?: return@forEach
            val shellPath = StorageUtils.resolvePathForShell(realPath)
            if (!Shell.cmd("[ -e '$shellPath' ]").exec().isSuccess) return@forEach

            plans.add(
                ItemPlan(
                    item = PerAppItem.CustomDir(uriStr),
                    paths = listOf(shellPath),
                    excludePatterns = emptyList(),
                    appMetadata = null,
                    customDirMetadata = mapOf(shellPath to CustomDirectoryMetadata(size = getDirectorySize(listOf(shellPath))))
                )
            )
        }

        return plans
    }

    private fun generateFilePathsForApp(app: AppInfo, t: BackupTypeSelection): List<String> {
        val uid = currentUserId
        return buildList {
            if (t.apk) {
                app.apkPaths.firstOrNull()?.let { File(it).parentFile?.absolutePath?.let { p -> add(p) } }
            }
            if (t.data) add("/data/user/$uid/${app.packageName}")
            if (t.deviceProtectedData) add("/data/user_de/$uid/${app.packageName}")
            if (t.externalData) add("/storage/emulated/$uid/Android/data/${app.packageName}")
            if (t.obb) add("/storage/emulated/$uid/Android/obb/${app.packageName}")
            if (t.media) add("/storage/emulated/$uid/Android/media/${app.packageName}")
        }
    }

    private val currentUserId: Int
        get() = try {
            android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
        } catch (_: Exception) {
            android.os.Process.myUid() / 100000
        }

    private fun getDirectorySize(paths: List<String>): Long {
        if (paths.isEmpty()) return 0L
        val result = Shell.cmd("du -sb ${paths.joinToString(" ") { "'$it'" }}").exec()
        var total = 0L
        if (result.isSuccess) {
            result.out.forEach { line ->
                total += line.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
            }
        }
        return total
    }

    private data class ItemPlan(
        val item: PerAppItem,
        val paths: List<String>,
        val excludePatterns: List<String>,
        val appMetadata: Pair<String, AppMetadata>?,
        val customDirMetadata: Map<String, CustomDirectoryMetadata>?
    )
}
