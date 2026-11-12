package com.example.braviaeco

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Actions communes aux widgets « design » : envoi d'une touche, écran éteint. */
object DesignActions {

    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    /** Envoie une touche à l'appareil visé par la télécommande (volume et muet restent sur la TV). */
    fun key(ctx: Context, name: String): Result<Unit> {
        val toDecoder = Prefs.target(ctx) == Target.DECODER &&
            name !in setOf("VolumeUp", "VolumeDown", "Mute")
        return if (toDecoder) Decoder.sendKey(ctx, name) else BraviaClient.sendIrcc(ctx, name)
    }

    /** Mode « Image off » : écran éteint, son conservé. Renvoie un message lisible. À appeler hors du thread principal. */
    fun togglePictureOff(ctx: Context): String {
        val current = BraviaClient.getMode(ctx).getOrNull()
        return if (current == EcoMode.PICTURE_OFF) {
            val back = EcoMode.fromApi(sp(ctx).getString("mode_before_picture_off", null))
                ?.takeIf { it != EcoMode.PICTURE_OFF } ?: EcoMode.LOW
            BraviaClient.setMode(ctx, back).fold(
                onSuccess = { "Écran rallumé" },
                onFailure = { "Échec : ${it.message}" }
            )
        } else {
            if (current != null) sp(ctx).edit().putString("mode_before_picture_off", current.api).apply()
            BraviaClient.setMode(ctx, EcoMode.PICTURE_OFF).fold(
                onSuccess = { "Écran éteint, son conservé" },
                onFailure = { "Échec : ${it.message}" }
            )
        }
    }
}

/**
 * Base des widgets « design » : chaque bouton envoie la même diffusion avec une commande ([EXTRA_CMD]).
 * Les sous-classes déclarent leur mise en page, leurs boutons et ce que fait chaque commande.
 */
abstract class DesignWidget : AppWidgetProvider() {

    protected abstract val layoutId: Int
    protected abstract val codeBase: Int

    /** Identifiant de vue → commande. */
    protected abstract fun buttons(): Map<Int, String>

    /** Exécute la commande hors du thread principal ; renvoie un message à afficher, ou null. */
    protected abstract fun run(context: Context, cmd: String): String?

    /** Texte d'état dynamique (en-tête), ou rien. */
    protected open fun decorate(context: Context, views: RemoteViews) {}

    /** Les widgets à rafraîchir après une action. */
    protected open fun refreshAfter(context: Context) = refresh(context)

    private fun action() = "com.example.braviaeco.DW_" + javaClass.simpleName

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != action()) return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        val pending = goAsync()
        thread {
            try {
                run(context, cmd)?.let { Ui.toast(context, it) }
            } finally {
                refreshAfter(context)
                pending.finish()
            }
        }
    }

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
        if (ids.isEmpty()) return
        val views = build(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun build(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        buttons().entries.forEachIndexed { i, (viewId, cmd) ->
            val pi = if (cmd == CMD_SOURCE) {
                WidgetIntents.activity(context, SourceActivity::class.java, codeBase + i)
            } else {
                WidgetIntents.broadcast(context, javaClass, action(), codeBase + i, EXTRA_CMD, cmd)
            }
            views.setOnClickPendingIntent(viewId, pi)
        }
        decorate(context, views)
        return views
    }

    /** Envoie une touche et renvoie un message d'erreur éventuel. */
    protected fun send(context: Context, name: String): String? =
        DesignActions.key(context, name).exceptionOrNull()?.let { it.message ?: "Échec" }

    companion object {
        const val EXTRA_CMD = "cmd"
        const val CMD_SOURCE = "@source"
    }
}

/** Image : profils 4K / upscalé, emplacement mémorisé suivant, écran éteint. */
class ImageDesignWidget : DesignWidget() {
    override val layoutId = R.layout.dw_image
    override val codeBase = 9100

    override fun buttons() = mapOf(
        R.id.dw_img_4k to "4k",
        R.id.dw_img_up to "up",
        R.id.dw_img_slot to "slot",
        R.id.dw_img_off to "off"
    )

    private fun sp(context: Context) = context.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    override fun run(context: Context, cmd: String): String? = when (cmd) {
        "4k", "up" -> {
            val kind = if (cmd == "4k") Picture.Kind.NATIVE_4K else Picture.Kind.UPSCALED
            Picture.adapt(context, kind).fold(
                onSuccess = {
                    sp(context).edit().putString("dw_image_last", kind.label).apply()
                    "Image : ${kind.label}"
                },
                onFailure = { "Échec : ${it.message}" }
            )
        }
        "slot" -> {
            val name = PictureSlots.next(context)
            val values = name?.let { PictureSlots.get(context, it) }
            if (name == null || values == null) {
                "Aucun profil mémorisé : appui long sur le bouton image de la télécommande"
            } else {
                Picture.applyValues(context, values).fold(
                    onSuccess = {
                        PictureSlots.markUsed(context, name)
                        sp(context).edit().putString("dw_image_last", name).apply()
                        "Image : $name"
                    },
                    onFailure = { "Échec : ${it.message}" }
                )
            }
        }
        else -> DesignActions.togglePictureOff(context)
    }

    override fun decorate(context: Context, views: RemoteViews) {
        views.setTextViewText(R.id.dw_img_status, sp(context).getString("dw_image_last", null) ?: "Prêt")
        val next = PictureSlots.next(context)
        views.setTextViewText(R.id.dw_img_slot_label, next ?: "Profil")
    }
}

/** Veille : minuteur d'extinction en un geste. */
class SleepDesignWidget : DesignWidget() {
    override val layoutId = R.layout.dw_sleep
    override val codeBase = 9200

    override fun buttons() = mapOf(
        R.id.dw_sleep_15 to "15",
        R.id.dw_sleep_30 to "30",
        R.id.dw_sleep_60 to "60",
        R.id.dw_sleep_90 to "90",
        R.id.dw_sleep_cancel to "cancel"
    )

    override fun run(context: Context, cmd: String): String? {
        if (cmd == "cancel") {
            SleepTimer.cancel(context)
            return "Minuteur annulé"
        }
        SleepTimer.set(context, cmd.toInt())
        return "La TV s'éteindra dans $cmd min"
    }

    override fun decorate(context: Context, views: RemoteViews) {
        val left = SleepTimer.remainingMinutes(context)
        val text = if (left == null) {
            "Aucun minuteur"
        } else {
            val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + left * 60_000L))
            "Éteint à $at"
        }
        views.setTextViewText(R.id.dw_sleep_status, text)
    }

    companion object {
        fun refreshAll(context: Context) = SleepDesignWidget().refresh(context)
    }
}

/** Croix compacte : directions, OK, retour, home, source, options. */
class PadDesignWidget : DesignWidget() {
    override val layoutId = R.layout.dw_pad
    override val codeBase = 9300

    override fun buttons() = mapOf(
        R.id.dw_pad_back to "Return",
        R.id.dw_pad_up to "Up",
        R.id.dw_pad_home to "Home",
        R.id.dw_pad_left to "Left",
        R.id.dw_pad_ok to "Confirm",
        R.id.dw_pad_right to "Right",
        R.id.dw_pad_source to CMD_SOURCE,
        R.id.dw_pad_down to "Down",
        R.id.dw_pad_options to "Options"
    )

    override fun run(context: Context, cmd: String): String? = send(context, cmd)
}

/** Lecture : précédent, retour rapide, lecture, pause, avance rapide, suivant. */
class MediaDesignWidget : DesignWidget() {
    override val layoutId = R.layout.dw_media
    override val codeBase = 9400

    override fun buttons() = mapOf(
        R.id.dw_media_prev to "Prev",
        R.id.dw_media_rew to "Rewind",
        R.id.dw_media_play to "Play",
        R.id.dw_media_pause to "Pause",
        R.id.dw_media_fwd to "Forward",
        R.id.dw_media_next to "Next"
    )

    override fun run(context: Context, cmd: String): String? = send(context, cmd)

    override fun decorate(context: Context, views: RemoteViews) {
        views.setTextViewText(
            R.id.dw_media_status,
            if (Prefs.target(context) == Target.DECODER) "Décodeur" else "TV"
        )
    }
}

/** Pavé numérique : chiffres, retour, OK. */
class NumpadDesignWidget : DesignWidget() {
    override val layoutId = R.layout.dw_numpad
    override val codeBase = 9500

    override fun buttons() = mapOf(
        R.id.dw_num_1 to "Num1", R.id.dw_num_2 to "Num2", R.id.dw_num_3 to "Num3",
        R.id.dw_num_4 to "Num4", R.id.dw_num_5 to "Num5", R.id.dw_num_6 to "Num6",
        R.id.dw_num_7 to "Num7", R.id.dw_num_8 to "Num8", R.id.dw_num_9 to "Num9",
        R.id.dw_num_back to "Return", R.id.dw_num_0 to "Num0", R.id.dw_num_ok to "Confirm"
    )

    override fun run(context: Context, cmd: String): String? = send(context, cmd)

    override fun decorate(context: Context, views: RemoteViews) {
        views.setTextViewText(
            R.id.dw_num_status,
            if (Prefs.target(context) == Target.DECODER) "Décodeur" else "TV"
        )
    }
}
