package com.example.gitzippush

import android.app.Application

/** Charge les règles d'accès avant toute requête GitHub (activités, services, widget). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AccessGuard.init(this)
    }
}
