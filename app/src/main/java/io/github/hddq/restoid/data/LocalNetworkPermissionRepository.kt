package io.github.hddq.restoid.data

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class LocalNetworkPermissionState {
    object Granted : LocalNetworkPermissionState()
    object Denied : LocalNetworkPermissionState()
    object NotRequested : LocalNetworkPermissionState()
}

class LocalNetworkPermissionRepository(private val context: Context) {
    private val _permissionState = MutableStateFlow<LocalNetworkPermissionState>(LocalNetworkPermissionState.NotRequested)
    val permissionState = _permissionState.asStateFlow()

    fun checkPermissionStatus() {
        val hasPermission = if (Build.VERSION.SDK_INT >= 37) {
            ContextCompat.checkSelfPermission(
                context,
                "android.permission.ACCESS_LOCAL_NETWORK"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Granted by default on older Android versions
        }

        _permissionState.value = if (hasPermission) {
            LocalNetworkPermissionState.Granted
        } else {
            LocalNetworkPermissionState.Denied
        }
    }
}
