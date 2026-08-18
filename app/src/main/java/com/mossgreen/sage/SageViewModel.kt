package com.mossgreen.sage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.mossgreen.sage.manager.CommandManager
import com.mossgreen.sage.manager.MonitoredChatsManager
import com.mossgreen.sage.manager.StatsManager

class SageViewModel(application: Application) : AndroidViewModel(application) {
    val prefs: SharedPreferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val keyManager = (application as SageApp).keyManager
    val commandManager = CommandManager(application)
    val statsManager = StatsManager(application)
    val monitoredChatsManager = MonitoredChatsManager(application)
}
