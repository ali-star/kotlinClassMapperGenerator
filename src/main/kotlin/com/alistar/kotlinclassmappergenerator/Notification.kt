package com.alistar.kotlinclassmappergenerator

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import javax.swing.Timer

sealed class Notification {

    companion object {
        private const val NOTIFICATION_TIMEOUT = 5000
    }

    protected val notificationGroup: NotificationGroup = NotificationGroupManager
        .getInstance()
        .getNotificationGroup("Kotlin Mapper Generator")

    class ErrorNotification : Notification() {

        fun show(project: Project, message: String) {
            val notification = notificationGroup.createNotification(message, NotificationType.ERROR)
            notification.notify(project)

            Timer(NOTIFICATION_TIMEOUT) {
                notification.expire()
            }.start()
        }
    }
}