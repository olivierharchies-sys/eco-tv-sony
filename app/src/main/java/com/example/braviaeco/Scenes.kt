package com.example.braviaeco

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** Scène : un nom et une suite d'étapes (allumer, source, mode éco, volume, application…). */
data class Scene(val name: String, val steps: List<String>)

object ScenePrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    fun scenes(ctx: Context): List<Scene> {
        val text = sp(ctx).getString("scenes", null) ?: return defaults(ctx)
        val arr = runCatching { JSONArray(text) }.getOrElse { JSONArray() }
        val list = ArrayList<Scene>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val stepsArr = o.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArr.length()).map { stepsArr.optString(it) }.filter { it.isNotEmpty() }
            list.add(Scene(o.optString("name", "?"), steps))
        }
        return list
    }

    fun save(ctx: Context, scenes: List<Scene>) {
        val arr = JSONArray()
        for (s in scenes) {
            val steps = JSONArray()
            s.steps.forEach { steps.put(it) }
            arr.put(JSONObject().put("name", s.name).put("steps", steps))
        }
        sp(ctx).edit().putString("scenes", arr.toString()).apply()
        SlotWidgets.refreshAll(ctx)
    }

    /** Scènes proposées tant que rien n'a été enregistré. */
    private fun defaults(ctx: Context): List<Scene> {
        val watch = ArrayList<String>()
        watch.add(SceneRunner.POWER_ON)
        val uri = ShortcutPrefs.decoderInputUri(ctx)
        if (uri.isNotEmpty()) watch.add("INPUT:" + uri + "|" + ShortcutPrefs.decoderInputName(ctx))
        return listOf(Scene("Regarder la TV", watch), Scene("Tout éteindre", listOf(SceneRunner.POWER_OFF)))
    }
}

/** Exécute une scène. À appeler hors du thread principal (appli, widget). */
object SceneRunner {
    const val POWER_ON = "POWER_ON"
    const val POWER_OFF = "POWER_OFF"

    private fun first(text: String): String {
        val i = text.indexOf('|')
        return if (i < 0) text else text.substring(0, i)
    }

    private fun second(text: String): String {
        val i = text.indexOf('|')
        return if (i < 0) text else text.substring(i + 1)
    }

    fun label(step: String): String = when {
        step == POWER_ON -> "Allumer la TV"
        step == POWER_OFF -> "Éteindre la TV"
        step == ShortcutPrefs.WAIT -> "Pause 1 s"
        step.startsWith("INPUT:") -> "Source " + second(step.substring(6))
        step.startsWith("ECO:") -> "Éco " + (EcoMode.fromApi(step.substring(4))?.label ?: "?")
        step.startsWith("VOL:") -> "Volume " + step.substring(4)
        step.startsWith("TVAPP:") -> "Appli " + second(step.substring(6))
        step.startsWith("DEC:") -> "Décodeur : " + step.substring(4)
        else -> step
    }

    fun run(ctx: Context, scene: Scene): Result<Unit> {
        val result = runCatching {
            for (step in scene.steps) runStep(ctx, step)
        }
        PowerWidgetProvider.refreshAll(ctx)
        SourceWidgetProvider.refreshAll(ctx)
        EcoWidgetProvider.refreshAll(ctx)
        return result
    }

    private fun runStep(ctx: Context, step: String) {
        if (step == POWER_ON) {
            if (BraviaClient.powerOn(ctx).getOrThrow()) Thread.sleep(6000)
        } else if (step == POWER_OFF) {
            BraviaClient.setPower(ctx, false).getOrThrow()
        } else if (step == ShortcutPrefs.WAIT) {
            Thread.sleep(1000)
        } else if (step.startsWith("INPUT:")) {
            val body = step.substring(6)
            val uri = first(body)
            val name = second(body)
            if (uri == "tv") {
                BraviaClient.sendIrcc(ctx, "Tv").getOrThrow()
            } else {
                BraviaClient.setInput(ctx, TvInput(uri, name, "", true)).getOrThrow()
            }
            Prefs.saveSource(ctx, name)
            Thread.sleep(1500)
        } else if (step.startsWith("ECO:")) {
            val mode = EcoMode.fromApi(step.substring(4)) ?: error("Mode éco inconnu")
            BraviaClient.setMode(ctx, mode).getOrThrow()
        } else if (step.startsWith("VOL:")) {
            val volume = step.substring(4).toIntOrNull() ?: error("Volume invalide")
            BraviaClient.setVolume(ctx, volume).getOrThrow()
        } else if (step.startsWith("TVAPP:")) {
            BraviaClient.launchApp(ctx, first(step.substring(6))).getOrThrow()
        } else if (step.startsWith("DEC:")) {
            val name = step.substring(4)
            val app = ShortcutPrefs.decoderApps(ctx).firstOrNull { it.name == name }
                ?: error("Raccourci décodeur « $name » introuvable")
            Decoder.run(ctx, app.keys).getOrThrow()
        } else {
            BraviaClient.sendIrcc(ctx, step).getOrThrow()
        }
    }
}

/** Minuterie de veille : éteint la TV après le délai choisi. */
object SleepTimer {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("bravia_eco", Context.MODE_PRIVATE)

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 9901, Intent(ctx, SleepReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun set(ctx: Context, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
        sp(ctx).edit().putLong("sleep_at", at).apply()
    }

    fun cancel(ctx: Context) {
        val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(intent(ctx))
        clear(ctx)
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove("sleep_at").apply()
    }

    /** Minutes restantes, ou null si aucune minuterie n'est en cours. */
    fun remainingMinutes(ctx: Context): Int? {
        val at = sp(ctx).getLong("sleep_at", 0L)
        val left = at - System.currentTimeMillis()
        if (at == 0L || left <= 0L) return null
        return ((left + 59_999L) / 60_000L).toInt()
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                SleepTimer.clear(context)
                BraviaClient.setPower(context, false)
                PowerWidgetProvider.refreshAll(context)
                SleepDesignWidget.refreshAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Fenêtres des scènes : lancer, créer, modifier, minuterie de veille. */
class SceneUi(private val act: Activity, private val actions: TvActions) {

    private fun dialog() = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)

    fun show() {
        val scenes = ScenePrefs.scenes(act)
        val left = SleepTimer.remainingMinutes(act)
        val timer = if (left == null) "⏱  Minuterie de veille…" else "⏱  Veille dans $left min (modifier)"
        val labels = (scenes.map { "▶  " + it.name } + timer + "✎  Gérer les scènes…").toTypedArray()
        dialog()
            .setTitle("Scènes")
            .setItems(labels) { _, which ->
                when {
                    which < scenes.size -> run(scenes[which])
                    which == scenes.size -> showSleepTimer()
                    else -> manage()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun run(scene: Scene) {
        Ui.toast(act, "Scène « ${scene.name} »…")
        actions.runIo {
            val r = SceneRunner.run(act, scene)
            actions.notifyStateChanged()
            r
        }
    }

    fun showSleepTimer() {
        val minutes = intArrayOf(15, 30, 45, 60, 90, 120)
        val labels = (minutes.map { "$it minutes" } + "Annuler la minuterie").toTypedArray()
        dialog()
            .setTitle("Éteindre la TV dans…")
            .setItems(labels) { _, which ->
                if (which < minutes.size) {
                    SleepTimer.set(act, minutes[which])
                    Ui.toast(act, "La TV s'éteindra dans ${minutes[which]} min")
                } else {
                    SleepTimer.cancel(act)
                    Ui.toast(act, "Minuterie annulée")
                }
            }
            .show()
    }

    private fun manage() {
        val scenes = ScenePrefs.scenes(act)
        val labels = (listOf("＋  Nouvelle scène") + scenes.map { "✎  " + it.name }).toTypedArray()
        dialog()
            .setTitle("Gérer les scènes")
            .setItems(labels) { _, which -> edit(if (which == 0) null else which - 1) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun edit(index: Int?) {
        val all = ScenePrefs.scenes(act).toMutableList()
        val original = if (index == null) null else all[index]
        val steps = ArrayList<String>(original?.steps ?: emptyList())

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(act, 20)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)

        val name = EditText(act)
        name.hint = "Nom (ex. Soirée film, Infos du matin…)"
        name.setSingleLine(true)
        name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.setTextColor(Ui.TEXT)
        name.setHintTextColor(Ui.DIM)
        name.setText(original?.name ?: "")
        box.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val caption = TextView(act)
        caption.text = "Étapes, dans l'ordre :"
        caption.setTextColor(Ui.DIM)
        caption.textSize = 13f
        caption.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6))
        box.addView(caption)

        val sequence = TextView(act)
        sequence.setTextColor(Ui.TEXT)
        sequence.textSize = 15f
        sequence.background = Ui.rounded(act, Ui.SURFACE_HI, 12)
        sequence.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
        sequence.minHeight = Ui.dp(act, 48)
        box.addView(sequence, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun render() {
            sequence.text = if (steps.isEmpty()) "(aucune étape)" else steps.joinToString("  →  ") { SceneRunner.label(it) }
        }
        render()

        val buttons = LinearLayout(act)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.setPadding(0, Ui.dp(act, 10), 0, 0)
        val gap = Ui.dp(act, 4)
        fun addButton(text: String, onClick: () -> Unit) {
            val b = Ui.pill(act, text, 13f)
            b.background = Ui.rounded(act, Ui.SURFACE_HI, 16)
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, Ui.dp(act, 42), 1f)
            lp.setMargins(gap, 0, gap, 0)
            buttons.addView(b, lp)
        }
        addButton("＋ Étape") { pickStep { steps.add(it); render() } }
        addButton("⌫ Effacer") {
            if (steps.isNotEmpty()) {
                steps.removeAt(steps.size - 1)
                render()
            }
        }
        addButton("▶ Tester") {
            if (steps.isEmpty()) Ui.toast(act, "Ajoute d'abord des étapes")
            else run(Scene(name.text.toString(), ArrayList(steps)))
        }
        box.addView(buttons)

        val builder = dialog()
            .setTitle(if (original == null) "Nouvelle scène" else "Modifier la scène")
            .setView(box)
            .setPositiveButton("Enregistrer") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty() || steps.isEmpty()) {
                    Ui.toast(act, "Il faut un nom et au moins une étape")
                } else {
                    val scene = Scene(n, ArrayList(steps))
                    if (index == null) all.add(scene) else all[index] = scene
                    ScenePrefs.save(act, all)
                    show()
                }
            }
            .setNegativeButton("Annuler", null)
        if (index != null) {
            builder.setNeutralButton("Supprimer") { _, _ ->
                all.removeAt(index)
                ScenePrefs.save(act, all)
                manage()
            }
        }
        builder.show()
    }

    private fun pickStep(onPick: (String) -> Unit) {
        val labels = arrayOf(
            "Allumer la TV", "Éteindre la TV", "Source…", "Mode éco…", "Volume…",
            "Appli de la TV…", "Raccourci du décodeur…", "Pause 1 s"
        )
        dialog()
            .setTitle("Ajouter une étape")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> onPick(SceneRunner.POWER_ON)
                    1 -> onPick(SceneRunner.POWER_OFF)
                    2 -> pickSource(onPick)
                    3 -> pickEco(onPick)
                    4 -> pickVolume(onPick)
                    5 -> pickTvApp(onPick)
                    6 -> pickDecoderShortcut(onPick)
                    else -> onPick(ShortcutPrefs.WAIT)
                }
            }
            .show()
    }

    private fun pickSource(onPick: (String) -> Unit) {
        thread {
            val inputs = BraviaClient.getInputs(act).getOrNull()?.takeIf { it.isNotEmpty() }
                ?: (1..4).map { TvInput("extInput:hdmi?port=$it", "HDMI $it", "", true) }
            act.runOnUiThread {
                if (!act.isFinishing) {
                    val labels = (listOf("TV") + inputs.map { it.display }).toTypedArray()
                    dialog()
                        .setTitle("Source")
                        .setItems(labels) { _, which ->
                            if (which == 0) {
                                onPick("INPUT:tv|TV")
                            } else {
                                val input = inputs[which - 1]
                                onPick("INPUT:" + input.uri + "|" + input.display)
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun pickEco(onPick: (String) -> Unit) {
        val modes = EcoMode.values()
        dialog()
            .setTitle("Mode éco")
            .setItems(modes.map { it.label }.toTypedArray()) { _, which -> onPick("ECO:" + modes[which].api) }
            .show()
    }

    private fun pickVolume(onPick: (String) -> Unit) {
        val edit = EditText(act)
        edit.hint = "Volume (0 à 100)"
        edit.inputType = InputType.TYPE_CLASS_NUMBER
        edit.setTextColor(Ui.TEXT)
        edit.setHintTextColor(Ui.DIM)
        val box = LinearLayout(act)
        val pad = Ui.dp(act, 20)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog()
            .setTitle("Volume")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val v = edit.text.toString().trim().toIntOrNull()
                if (v == null || v < 0 || v > 100) Ui.toast(act, "Volume entre 0 et 100") else onPick("VOL:$v")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pickTvApp(onPick: (String) -> Unit) {
        val apps = ShortcutPrefs.tvApps(act)
        if (apps.isEmpty()) {
            Ui.toast(act, "Choisis d'abord tes applications (bouton des applis de la TV)")
            return
        }
        dialog()
            .setTitle("Appli de la TV")
            .setItems(apps.map { it.title }.toTypedArray()) { _, which ->
                onPick("TVAPP:" + apps[which].uri + "|" + apps[which].title)
            }
            .show()
    }

    private fun pickDecoderShortcut(onPick: (String) -> Unit) {
        val apps = ShortcutPrefs.decoderApps(act)
        if (apps.isEmpty()) {
            Ui.toast(act, "Crée d'abord un raccourci (bouton des applis du décodeur)")
            return
        }
        dialog()
            .setTitle("Raccourci du décodeur")
            .setItems(apps.map { it.name }.toTypedArray()) { _, which -> onPick("DEC:" + apps[which].name) }
            .show()
    }
}
