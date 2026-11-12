package com.github.jing332.tts_server_android.service.forwarder

import android.content.Context
import android.content.Intent
import com.github.jing332.tts_server_android.service.forwarder.system.SysTtsForwarderService

object ForwarderServiceManager {
    fun Context.switchSysTtsForwarder() {
        if (SysTtsForwarderService.isRunning) {
            closeSysTtsForwarder()
        } else {
            startSysTtsForwarder()
        }
    }

    fun Context.startSysTtsForwarder() {
        val intent = Intent(this, SysTtsForwarderService::class.java)
        // 必须走 startForegroundService：普通 startService 起的服务在 App 退到后台后
        // 会被系统当后台服务清理（现象就是“切到阅读再回来变成停止”）
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }

    fun closeSysTtsForwarder() {
        SysTtsForwarderService.instance?.close()
    }
}