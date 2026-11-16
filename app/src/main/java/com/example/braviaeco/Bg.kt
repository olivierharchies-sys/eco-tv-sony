package com.example.braviaeco

import android.content.BroadcastReceiver
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Travail réseau en arrière-plan. Un seul ensemble de fils partagé par toute l'appli (au lieu d'un fil
 * neuf à chaque appui) ; les fils inactifs sont libérés au bout d'une minute. Aucune dépendance AndroidX.
 */
object Bg {
    private val counter = AtomicInteger()

    private val pool = Executors.newCachedThreadPool(ThreadFactory { task ->
        Thread(task, "eco-bg-" + counter.incrementAndGet()).apply { isDaemon = true }
    })

    /** Exécute [work] hors du thread principal. */
    fun run(work: () -> Unit) {
        pool.execute(work)
    }
}

/**
 * Traite un message reçu en arrière-plan sans que le système ne coupe le récepteur : [work] tourne sur [Bg],
 * puis [after] (rafraîchissement d'un widget, par exemple), puis le récepteur est libéré — même en cas d'erreur.
 * À appeler depuis `onReceive`.
 */
fun BroadcastReceiver.inBackground(after: () -> Unit = {}, work: () -> Unit) {
    val pending = goAsync()
    Bg.run {
        try {
            work()
        } finally {
            try {
                after()
            } finally {
                pending.finish()
            }
        }
    }
}
