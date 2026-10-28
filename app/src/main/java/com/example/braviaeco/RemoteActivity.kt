package com.example.braviaeco

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Télécommande complète : alimentation, source, navigation, volume, lecture, couleurs, chiffres. */
class RemoteActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val pending = AtomicInteger(0)
    private var lastToast = 0L

    private lateinit var sourcesBox: LinearLayout
    private lateinit var sourcesStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Télécommande")

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(8), dp(12), dp(28))

        // --- Alimentation
        root.addView(section("Alimentation"))
        root.addView(
            row(
                button("Allumer") { runIo { BraviaClient.setPower(this, true) } },
                button("Éteindre") { runIo { BraviaClient.setPower(this, false) } }
            )
        )

        // --- Source
        root.addView(section("Source"))
        sourcesStatus = TextView(this)
        sourcesStatus.setPadding(dp(4), 0, dp(4), dp(4))
        sourcesBox = LinearLayout(this)
        sourcesBox.orientation = LinearLayout.VERTICAL
        root.addView(sourcesStatus)
        root.addView(sourcesBox)

        // --- Navigation
        root.addView(section("Navigation"))
        root.addView(row(View(this), key("▲", "Up", repeat = true), View(this)))
        root.addView(row(key("◀", "Left", repeat = true), key("OK", "Confirm"), key("▶", "Right", repeat = true)))
        root.addView(row(View(this), key("▼", "Down", repeat = true), View(this)))
        root.addView(row(key("Retour", "Return", "Back"), key("Accueil", "Home"), key("Options", "Options")))

        // --- Volume / chaînes
        root.addView(section("Volume et chaînes"))
        root.addView(
            row(
                key("Vol −", "VolumeDown", repeat = true),
                key("Muet", "Mute"),
                key("Vol +", "VolumeUp", repeat = true)
            )
        )
        root.addView(
            row(
                key("Chaîne −", "ChannelDown", repeat = true),
                key("Info", "Display"),
                key("Chaîne +", "ChannelUp", repeat = true)
            )
        )

        // --- Lecture
        root.addView(section("Lecture"))
        root.addView(
            row(
                key("Préc.", "Prev"),
                key("«", "Rewind"),
                key("Lecture", "Play"),
                key("Pause", "Pause")
            )
        )
        root.addView(row(key("Stop", "Stop"), key("»", "Forward"), key("Suiv.", "Next")))

        // --- Couleurs
        root.addView(section("Touches couleur"))
        root.addView(
            row(
                tinted(key("", "Red"), "#D93025"),
                tinted(key("", "Green"), "#1E8E3E"),
                tinted(key("", "Yellow"), "#F9AB00"),
                tinted(key("", "Blue"), "#1A73E8")
            )
        )

        // --- Chiffres
        root.addView(section("Chiffres"))
        root.addView(row(key("1", "Num1"), key("2", "Num2"), key("3", "Num3")))
        root.addView(row(key("4", "Num4"), key("5", "Num5"), key("6", "Num6")))
        root.addView(row(key("7", "Num7"), key("8", "Num8"), key("9", "Num9")))
        root.addView(row(key("Source TV", "Input"), key("0", "Num0"), key("Netflix", "Netflix")))

        // --- Réglages
        root.addView(section("Connexion"))
        root.addView(
            row(
                button("Réglages / économie d'énergie") {
                    startActivity(Intent(this, MainActivity::class.java))
                }
            )
        )

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        loadSources()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    // ------------------------------------------------------------------ sources

    private fun loadSources() {
        sourcesStatus.text = "Chargement des sources…"
        sourcesBox.removeAllViews()
        io.execute {
            val result = BraviaClient.getInputs(this)
            ui.post {
                if (isFinishing) return@post
                val inputs = result.getOrNull()
                sourcesStatus.text = if (inputs == null) {
                    "Sources indisponibles : " + (result.exceptionOrNull()?.message ?: "erreur")
                } else {
                    ""
                }
                showSources(inputs ?: emptyList())
            }
        }
    }

    private fun showSources(inputs: List<TvInput>) {
        sourcesBox.removeAllViews()

        val buttons = ArrayList<View>()
        buttons.add(button("TV") { runIo { BraviaClient.sendIrcc(this, "Tv") } })
        for (input in inputs) {
            val b = button(input.display) {
                runIo {
                    val r = BraviaClient.setInput(this, input)
                    if (r.isSuccess) ui.post { sourcesStatus.text = "Source : " + input.display }
                    r
                }
            }
            if (!input.connected) b.alpha = 0.55f
            buttons.add(b)
        }
        buttons.add(button("Actualiser") { loadSources() })

        var i = 0
        while (i < buttons.size) {
            val first = buttons[i]
            val second: View = if (i + 1 < buttons.size) buttons[i + 1] else View(this)
            sourcesBox.addView(row(first, second))
            i += 2
        }
    }

    // ------------------------------------------------------------------ envoi

    /** Exécute un appel réseau dans l'ordre. skipIfBusy : ignore la commande si la précédente n'est pas finie. */
    private fun runIo(skipIfBusy: Boolean = false, work: () -> Result<*>) {
        if (skipIfBusy && pending.get() > 0) return
        pending.incrementAndGet()
        io.execute {
            val result = try {
                work()
            } finally {
                pending.decrementAndGet()
            }
            val error = result.exceptionOrNull()
            if (error != null) ui.post { toastError(error.message ?: "Échec") }
        }
    }

    private fun toastError(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToast < 2500) return
        lastToast = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ fabrique de vues

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun section(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.setPadding(dp(4), dp(18), dp(4), dp(4))
        return t
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            r.addView(v, lp)
        }
        return r
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 14f
        b.setAllCaps(false)
        b.minimumHeight = dp(52)
        b.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        return b
    }

    /** Touche de télécommande. [name] est le nom IRCC, [alias] un nom de repli éventuel. */
    private fun key(label: String, name: String, alias: String? = null, repeat: Boolean = false): Button {
        val names: Array<String> = if (alias == null) arrayOf(name) else arrayOf(name, alias)
        val send = { runIo(skipIfBusy = repeat) { BraviaClient.sendIrcc(this, *names) } }

        val b = button(label) { send() }
        if (repeat) {
            val ticker = object : Runnable {
                override fun run() {
                    send()
                    ui.postDelayed(this, 140)
                }
            }
            b.setOnClickListener(null)
            b.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        send()
                        ui.postDelayed(ticker, 450)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        ui.removeCallbacks(ticker)
                    }
                }
                true
            }
        }
        return b
    }

    private fun tinted(b: Button, color: String): Button {
        b.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
        return b
    }
}
