package com.example.gitzippush

/** Vérification d'un projet Android avant envoi : fichiers indispensables et dossiers suspects. */
object ZipCheck {

    class Result(val warnings: List<String>, val complete: Boolean, val android: Boolean = false)

    /** [paths] : chemins relatifs à la racine du projet (dossier racine du zip déjà retiré). */
    fun check(paths: Collection<String>, autoWorkflow: Boolean): Result {
        val set = paths.toSet()
        val android = paths.any { it.endsWith(".kt") || it.endsWith(".java") || it.endsWith("AndroidManifest.xml") }
        if (!android) return Result(emptyList(), false)
        val w = ArrayList<String>()
        val settings = "settings.gradle.kts" in set || "settings.gradle" in set
        if (!settings) {
            // projet rangé plus bas : « X/settings.gradle.kts »
            val nested = paths.filter { it.endsWith("/settings.gradle.kts") || it.endsWith("/settings.gradle") }
                .minByOrNull { it.count { c -> c == '/' } }
            if (nested != null) {
                val dir = nested.substringBeforeLast('/')
                w.add("projet dans le sous-dossier « $dir/ » : zippe ce dossier, ou il arrivera tel quel dans le dépôt")
                return Result(w, false, true)
            }
        }
        val appGradle = "app/build.gradle.kts" in set || "app/build.gradle" in set
        val manifest = "app/src/main/AndroidManifest.xml" in set
        if (!settings) w.add("settings.gradle(.kts) absent")
        if (!appGradle) w.add("app/build.gradle(.kts) absent")
        if (!manifest) w.add("app/src/main/AndroidManifest.xml absent")
        if (paths.none { it.startsWith(".github/workflows/") } && !autoWorkflow) {
            w.add("aucun workflow .github/workflows (ajout auto désactivé)")
        }
        if (paths.any { it.startsWith("src/main/") } && paths.any { it.startsWith("app/src/main/") }) {
            w.add("dossier src/ à la racine en plus de app/src/ : doublons probables")
        }
        val names = paths.filter { it.endsWith(".kt") || it.endsWith(".java") }
            .groupBy { it.substringAfterLast('/') + "|" + it.substringAfter("/java/", "") }
            .filter { it.value.size > 1 && it.key.substringAfter('|').isNotEmpty() }
        if (names.isNotEmpty()) {
            w.add("fichier(s) en double : " + names.keys.take(3).joinToString(", ") { it.substringBefore('|') })
        }
        return Result(w, settings && appGradle && manifest, true)
    }
}
