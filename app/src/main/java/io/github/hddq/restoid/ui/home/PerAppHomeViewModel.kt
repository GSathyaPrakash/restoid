package io.github.hddq.restoid.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.hddq.restoid.R
import io.github.hddq.restoid.data.PerAppItemDescriptor
import io.github.hddq.restoid.data.PerAppItemRegistry
import io.github.hddq.restoid.data.PerAppRepositoryResolver
import io.github.hddq.restoid.data.RepositoriesRepository
import io.github.hddq.restoid.data.ResticBinaryManager
import io.github.hddq.restoid.data.ResticRepository
import io.github.hddq.restoid.data.ResticState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PerAppItemSummary(
    val descriptor: PerAppItemDescriptor,
    val snapshotCount: Int,
    val lastBackupTime: String?
)

data class PerAppHomeUiState(
    val items: List<PerAppItemSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val isRepoReady: Boolean = false,
    val resticState: ResticState = ResticState.Idle,
    val pendingDeleteSlug: String? = null,
    val isDeleting: Boolean = false
)

/**
 * ViewModel for the per-app Home screen. Lists every app/directory that has its
 * own nested repository under the selected (base) repository, with snapshot
 * count and last-backup time, and supports deleting an item's complete history.
 *
 * All restic calls use the base repository's credentials/env/options against the
 * per-item derived path, exactly like [io.github.hddq.restoid.work.PerAppBackupOperationRunner].
 */
class PerAppHomeViewModel(
    private val context: Context,
    private val repositoriesRepository: RepositoriesRepository,
    private val resticBinaryManager: ResticBinaryManager,
    private val resticRepository: ResticRepository
) : ViewModel() {

    private val registry = PerAppItemRegistry(context)
    private val _uiState = MutableStateFlow(PerAppHomeUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            resticBinaryManager.checkResticStatus()
            combine(
                repositoriesRepository.selectedRepository,
                resticBinaryManager.resticState
            ) { key, state -> key to state }
                .collect { (key, state) ->
                    val ready = key != null &&
                        state is ResticState.Installed &&
                        repositoriesRepository.getRepositoryPassword(key) != null
                    _uiState.update { it.copy(resticState = state, isRepoReady = ready) }
                    when {
                        ready && key != null -> loadItems(key, refresh = false)
                        key == null -> _uiState.update { it.copy(items = emptyList(), error = null) }
                        else -> Unit
                    }
                }
        }
    }

    fun refresh() {
        val key = repositoriesRepository.selectedRepository.value ?: return
        viewModelScope.launch { loadItems(key, refresh = true) }
    }

    fun requestDelete(slug: String) {
        _uiState.update { it.copy(pendingDeleteSlug = slug) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(pendingDeleteSlug = null) }
    }

    fun confirmDelete() {
        val slug = _uiState.value.pendingDeleteSlug ?: return
        val key = repositoriesRepository.selectedRepository.value ?: return
        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            val baseRepo = repositoriesRepository.getRepositoryByKey(key)
            val password = repositoriesRepository.getRepositoryPassword(key)
            if (baseRepo == null || password == null) {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        pendingDeleteSlug = null,
                        error = context.getString(R.string.error_password_not_found_for_repository)
                    )
                }
                return@launch
            }
            val env = repositoriesRepository.getExecutionEnvironmentVariables(key)
            val options = repositoriesRepository.getExecutionResticOptions(key)
            val derivedPath = PerAppRepositoryResolver.deriveRepoPath(baseRepo, slug)
            val result = resticRepository.forgetAll(derivedPath, password, env, options)
            if (result.isSuccess) {
                registry.removeItem(key, slug)
            } else {
                _uiState.update {
                    it.copy(
                        error = result.exceptionOrNull()?.message
                            ?: context.getString(R.string.restic_failure_delete_snapshot)
                    )
                }
            }
            _uiState.update { it.copy(isDeleting = false, pendingDeleteSlug = null) }
            loadItems(key, refresh = false)
        }
    }

    private suspend fun loadItems(baseKey: String, refresh: Boolean) {
        _uiState.update { it.copy(isLoading = !refresh, isRefreshing = refresh, error = null) }
        val baseRepo = repositoriesRepository.getRepositoryByKey(baseKey)
        val password = repositoriesRepository.getRepositoryPassword(baseKey)
        if (baseRepo == null || password == null) {
            _uiState.update {
                it.copy(
                    items = emptyList(),
                    isLoading = false,
                    isRefreshing = false,
                    error = context.getString(R.string.error_password_not_found_for_repository)
                )
            }
            return
        }
        val env = repositoriesRepository.getExecutionEnvironmentVariables(baseKey)
        val options = repositoriesRepository.getExecutionResticOptions(baseKey)
        val descriptors = registry.getItems(baseKey)
        val summaries = descriptors.map { d ->
            val derivedPath = PerAppRepositoryResolver.deriveRepoPath(baseRepo, d.slug)
            val snapshots = resticRepository.getSnapshots(derivedPath, password, env, options)
                .getOrNull()
                ?: emptyList()
            val latest = snapshots.maxByOrNull { it.time }
            PerAppItemSummary(d, snapshots.size, latest?.time)
        }.sortedByDescending { it.lastBackupTime ?: "" }
        _uiState.update {
            it.copy(items = summaries, isLoading = false, isRefreshing = false, error = null)
        }
    }
}

class PerAppHomeViewModelFactory(
    private val context: Context,
    private val repositoriesRepository: RepositoriesRepository,
    private val resticBinaryManager: ResticBinaryManager,
    private val resticRepository: ResticRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PerAppHomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PerAppHomeViewModel(
                context,
                repositoriesRepository,
                resticBinaryManager,
                resticRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
