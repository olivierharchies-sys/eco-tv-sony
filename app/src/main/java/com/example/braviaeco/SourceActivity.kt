package com.example.braviaeco

import android.app.Activity
import android.os.Bundle

/** Fenêtre transparente lancée par le widget « Source » : affiche la liste des sources puis se ferme. */
class SourceActivity : Activity() {

    private lateinit var actions: TvActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actions = TvActions(this)
        actions.showSourcePicker { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        actions.close()
    }
}
