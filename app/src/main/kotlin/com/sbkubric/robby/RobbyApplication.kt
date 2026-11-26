package com.sbkubric.robby

import android.app.Application
import android.content.Context

class RobbyApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduleAlarms.follow(container.appScope)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as RobbyApplication).container
