package com.espitman.sdm.ui

/** The launch animation belongs to a new app session, not an Activity recreation. */
class SplashLaunchPolicy {
    private var launchedInThisProcess = false

    @Synchronized
    fun shouldShow(restoredActivity: Boolean): Boolean {
        val show = !restoredActivity && !launchedInThisProcess
        launchedInThisProcess = true
        return show
    }
}
