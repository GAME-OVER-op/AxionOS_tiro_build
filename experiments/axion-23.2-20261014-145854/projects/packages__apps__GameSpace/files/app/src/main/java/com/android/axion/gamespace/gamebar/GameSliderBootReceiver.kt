package com.android.axion.gamespace.gamebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GameSliderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { context.startService(Intent(context, GameSliderService::class.java)) }
            .onFailure { Log.e("GameSliderBoot", "Unable to start slider monitor", it) }
    }
}
