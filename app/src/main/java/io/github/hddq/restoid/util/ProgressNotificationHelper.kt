package io.github.hddq.restoid.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

class ProgressNotificationHelper(private val context: Context) {

    private val channelId = "task_progress_channel"
    private val notificationId = 2137 
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        // You must create a notification channel on Android 8.0+
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val name = "Task Progress"
        val descriptionText = "Shows background task progress"
        // IMPORTANCE_LOW is crucial so it doesn't pop up and make sounds continuously
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(channelId, name, importance).apply {
            description = descriptionText
        }
        
        val manager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    suspend fun startFakeDownload() {
        // Fail-safe check for POST_NOTIFICATIONS permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // If you don't have permission, just abort. 
            // Handle permission requests in your Activity/Fragment, not here.
            return
        }

        val maxProgress = 100
        
        val builder = NotificationCompat.Builder(context, channelId).apply {
            setContentTitle("Doing some heavy work")
            setContentText("Copying files...")
            setSmallIcon(android.R.drawable.stat_sys_download) 
            setPriority(NotificationCompat.PRIORITY_LOW)
            // Prevent sound/vibration on every single progress update
            setOnlyAlertOnce(true)
            // Prevent user from swiping it away while in progress
            setOngoing(true) 
            // Request Live Update promotion for API 36
            addExtras(Bundle().apply {
                putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
            })
        }

        // Send initial notification with 0% progress
        builder.setProgress(maxProgress, 0, false)
        notificationManager.notify(notificationId, builder.build())

        // Simulate a background task looping (e.g., writing chunks of data)
        for (progress in 1..maxProgress) {
            delay(50) // Simulating work taking some time
            
            // Update the progress value
            builder.setProgress(maxProgress, progress, false)
            // Re-issue the notification with the exact SAME ID to update it
            notificationManager.notify(notificationId, builder.build())
        }

        // Task is finished
        // Remove the progress bar by setting both values to 0 and false
        builder.setContentText("Task completed successfully!")
            .setProgress(0, 0, false)
            .setOngoing(false) // Allow user to dismiss it now
            // Change icon to a checkmark or something similar
            .setSmallIcon(android.R.drawable.stat_sys_download_done) 

        notificationManager.notify(notificationId, builder.build())
    }
}
