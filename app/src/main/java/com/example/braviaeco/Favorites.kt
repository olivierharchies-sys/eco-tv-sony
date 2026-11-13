package com.example.braviaeco

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Chaîne favorite du décodeur : un nom et son numéro. */
data class Channel(val name: String, val number: Int)

object ChannelPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun channels(ctx: Context): List<Channel> {
        val arr = runCatching { JSONArray(sp(ctx).getString("channels", "[]")) }.getOrElse { JSONArray() }
        val list = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(Channel(o.optString("name", "?"), o.optInt("number", 0)))
        }
        return list
    }

    fun save(ctx: Context, channels: List<Channel>) {
        val arr = JSONArray()
        for (c in channels) arr.put(JSONObject().put("name", c.name).put("number", c.number))
        sp(ctx).edit().putString("channels", arr.toString()).apply()
        SlotWidgets.refreshAll(ctx)
    }
}

/** Fenêtres des chaînes favorites : zapper, ajouter, supprimer. */
object Favorites {

    private fun dialog(act: Activity) = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)

    fun show(act: Activity, actions: TvActions) {
        val channels = ChannelPrefs.channels(act)
        val labels = ArrayList<String>()
        channels.forEach { labels.add("${it.number}  ·  ${it.name}") }
        labels.add("＋  Ajouter une chaîne")
        if (channels.isNotEmpty()) labels.add("✕  Supprimer une chaîne…")
        dialog(act)
            .setTitle("Chaînes favorites")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < channels.size -> {
                        val channel = channels[which]
                        actions.runIo {
                            val r = Decoder.zap(act, channel.number)
                            actions.notifyStateChanged()
                            r
                        }
                    }
                    which == channels.size -> add(act, actions)
                    else -> remove(act, actions)
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun add(act: Activity, actions: TvActions) {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, 20)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)

        val name = EditText(act)
        name.hint = "Nom (ex. La Une)"
        name.setSingleLine(true)
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.setTextColor(Ui.TEXT)
        name.setHintTextColor(Ui.DIM)
        box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val number = EditText(act)
        number.hint = "Numéro de la chaîne"
        number.inputType = InputType.TYPE_CLASS_NUMBER
        number.setTextColor(Ui.TEXT)
        number.setHintTextColor(Ui.DIM)
        box.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog(act)
            .setTitle("Nouvelle chaîne favorite")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val n = name.text.toString().trim()
                val num = number.text.toString().trim().toIntOrNull()
                if (n.isEmpty() || num == null || num < 0) {
                    Ui.toast(act, "Il faut un nom et un numéro")
                } else {
                    ChannelPrefs.save(act, ChannelPrefs.channels(act) + Channel(n, num))
                    show(act, actions)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun remove(act: Activity, actions: TvActions) {
        val channels = ChannelPrefs.channels(act)
        dialog(act)
            .setTitle("Supprimer")
            .setItems(channels.map { "${it.number}  ·  ${it.name}" }.toTypedArray()) { _, which ->
                ChannelPrefs.save(act, channels.filterIndexed { i, _ -> i != which })
                show(act, actions)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}

/** Widget à 8 cases (2 lignes de 4) : base commune aux scènes et aux chaînes favorites. */
abstract class SlotWidgetProvider : AppWidgetProvider() {

    protected abstract val title: String
    protected abstract val emptyHint: String
    protected abstract val action: String
    protected abstract val baseCode: Int

    protected abstract fun labels(context: Context): List<String>
    protected abstract fun perform(context: Context, index: Int): Result<*>

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildViews(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != action) return
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        if (index < 0) return

        val pending = goAsync()
        thread {
            try {
                val result = perform(context, index)
                result.exceptionOrNull()?.let { Ui.toast(context, it.message ?: "Échec") }
            } finally {
                pending.finish()
            }
        }
    }

    fun buildViews(context: Context): RemoteViews {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val views = RemoteViews(context.packageName, R.layout.widget_slots)
        val items = labels(context)
        views.setTextViewText(R.id.slots_title, if (items.isEmpty()) "$title · $emptyHint" else title)
        val open = Intent(context, RemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(
            R.id.slots_title,
            PendingIntent.getActivity(context, baseCode + 50, open, flags)
        )
        for (i in SLOTS.indices) {
            val id = SLOTS[i]
            if (i < items.size) {
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(LABELS[i], items[i])
                views.setTextViewText(BADGES[i], badge(items[i]))
                val intent = Intent(context, javaClass).setAction(action).putExtra(EXTRA_INDEX, i)
                views.setOnClickPendingIntent(id, PendingIntent.getBroadcast(context, baseCode + i, intent, flags))
            } else {
                views.setViewVisibility(id, View.INVISIBLE)
            }
        }
        return views
    }

    companion object {
        const val EXTRA_INDEX = "index"
        val SLOTS = intArrayOf(
            R.id.slot_1, R.id.slot_2, R.id.slot_3, R.id.slot_4,
            R.id.slot_5, R.id.slot_6, R.id.slot_7, R.id.slot_8
        )
        private val LABELS = intArrayOf(
            R.id.slot_1_label, R.id.slot_2_label, R.id.slot_3_label, R.id.slot_4_label,
            R.id.slot_5_label, R.id.slot_6_label, R.id.slot_7_label, R.id.slot_8_label
        )
        private val BADGES = intArrayOf(
            R.id.slot_1_badge, R.id.slot_2_badge, R.id.slot_3_badge, R.id.slot_4_badge,
            R.id.slot_5_badge, R.id.slot_6_badge, R.id.slot_7_badge, R.id.slot_8_badge
        )

        /** Texte du rond : numéro de chaîne s'il y en a un en tête, sinon l'initiale. */
        private fun badge(label: String): String {
            val t = label.trim()
            val digits = t.takeWhile { it.isDigit() }
            return if (digits.isNotEmpty()) digits.take(3) else t.take(1).uppercase()
        }
    }
}

/** Widget : une case par scène. */
class SceneWidgetProvider : SlotWidgetProvider() {
    override val title = "Scènes"
    override val emptyHint = "à créer dans l'appli"
    override val action = "com.example.braviaeco.RUN_SCENE"
    override val baseCode = 9300

    override fun labels(context: Context): List<String> = ScenePrefs.scenes(context).map { it.name }

    override fun perform(context: Context, index: Int): Result<*> {
        val scene = ScenePrefs.scenes(context).getOrNull(index) ?: return Result.success(Unit)
        Ui.toast(context, "Scène « ${scene.name} »…")
        return SceneRunner.run(context, scene)
    }
}

/** Widget : une case par chaîne favorite. */
class ChannelWidgetProvider : SlotWidgetProvider() {
    override val title = "Chaînes"
    override val emptyHint = "à ajouter dans l'appli"
    override val action = "com.example.braviaeco.ZAP_CHANNEL"
    override val baseCode = 9400

    override fun labels(context: Context): List<String> = ChannelPrefs.channels(context).map { it.name }

    override fun perform(context: Context, index: Int): Result<*> {
        val channel = ChannelPrefs.channels(context).getOrNull(index) ?: return Result.success(Unit)
        return Decoder.zap(context, channel.number)
    }
}

object SlotWidgets {
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val providers: List<SlotWidgetProvider> = listOf(SceneWidgetProvider(), ChannelWidgetProvider())
        for (provider in providers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
            if (ids.isEmpty()) continue
            val views = provider.buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
