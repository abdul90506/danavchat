package com.danav.chat

import android.app.Application
import com.danav.chat.call.CallManager
import com.danav.chat.data.Repo
import com.danav.chat.push.Notifier

class DanavApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        CallManager.init(this)
        Notifier.createChannels(this)
    }
}
