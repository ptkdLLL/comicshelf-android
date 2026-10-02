package com.comicshelf.app.util

import android.app.Application
import android.content.Context

object AppContextHolder {
    lateinit var cacheDir: java.io.File
        private set
    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
        cacheDir = application.cacheDir
    }
}

fun initAppContext(app: Application) {
    AppContextHolder.init(app)
}
