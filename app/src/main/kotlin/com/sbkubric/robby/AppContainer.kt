package com.sbkubric.robby

import android.content.Context
import com.sbkubric.robby.service.ServiceLauncher

/** Manual DI: the single place where the app's objects are built and wired. */
class AppContainer(context: Context) {
    val serviceLauncher = ServiceLauncher(context.applicationContext)
}
