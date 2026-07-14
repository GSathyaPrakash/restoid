package io.github.hddq.restoid.ui.schedules

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.hddq.restoid.data.AppInfoRepository
import io.github.hddq.restoid.data.RepositoriesRepository
import io.github.hddq.restoid.data.ScheduleRepository
import io.github.hddq.restoid.data.RootRepository

class SchedulesViewModelFactory(
    private val application: Application,
    private val scheduleRepository: ScheduleRepository,
    private val repositoriesRepository: RepositoriesRepository,
    private val appInfoRepository: AppInfoRepository,
    private val rootRepository: RootRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SchedulesViewModel::class.java)) {
            return SchedulesViewModel(application, scheduleRepository, repositoriesRepository, appInfoRepository, rootRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
