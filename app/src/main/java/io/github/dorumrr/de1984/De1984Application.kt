package io.github.dorumrr.de1984

import android.app.Application

class De1984Application : Application() {

    lateinit var dependencies: De1984Dependencies
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize dependencies
        dependencies = De1984Dependencies.getInstance(this)
    }
}
