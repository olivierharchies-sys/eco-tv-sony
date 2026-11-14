package com.example.gitzippush

/**
 * Place automatiquement un fichier envoyé seul (ex. fichier fourni par Claude) au bon endroit du dépôt :
 * 1. ligne repère dans les 5 premières lignes : « // chemin: app/src/.../X.kt » (ou path:, # , <!-- -->…) ;
 * 2. sinon fichier du même nom déjà présent dans le dépôt (départagé par « package » puis par le dossier le plus fourni) ;
 * 3. sinon dossier du « package » Kotlin/Java s'il existe ; sinon racine (ou dossier cible).
 */
object Placer {
    private val MARK = Regex(
        """^\s*(?://+|#+|<!--|/\*+|\*|--|;+|%+)?\s*(?:chemin|path|fichier|file)\s*[:=]\s*([A-Za-z0-9_.@+\-][A-Za-z0-9_./@+\- ]*?)\s*(?:-->|\*/)?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val PACKAGE = Regex("""^\s*package\s+([A-Za-z_][\w.]*)\s*;?\s*$""", RegexOption.MULTILINE)
    private val COPY_SUFFIX = Regex("""\s*\(\d+\)(?=\.[^.]+$|$)""")

    /** Chemin indiqué dans le fichier lui-même, ou null. */
    fun marker(text: String?): String? {
        if (text == null) return null
        for (line in text.lineSequence().take(5)) {
            val m = MARK.matchEntire(line) ?: continue
            val p = m.groupValues[1].trim().trim('/')
            if ('/' in p) return ZipTools.cleanName(p)
        }
        return null
    }

    /** Nom sans « (1) » ajouté par le téléchargement d'Android. */
    fun baseName(name: String): String =
        name.substringAfterLast('/').replace(COPY_SUFFIX, "")

    /** Chemin trouvé dans le dépôt, ou null (nouveau fichier : emplacement par défaut). */
    fun match(name: String, text: String?, existing: Collection<String>): String? {
        val base = baseName(name)
        val pkgDir = text?.let { PACKAGE.find(it) }?.groupValues?.get(1)?.replace('.', '/')
        val dirCount = HashMap<String, Int>()
        for (p in existing) {
            val d = p.substringBeforeLast('/', "")
            dirCount[d] = (dirCount[d] ?: 0) + 1
        }
        fun dirOf(p: String) = p.substringBeforeLast('/', "")

        var cands = existing.filter { it.substringAfterLast('/') == base }
        if (cands.size > 1 && pkgDir != null) {
            val byPkg = cands.filter { dirOf(it).endsWith(pkgDir) }
            if (byPkg.isNotEmpty()) cands = byPkg
        }
        if (cands.size > 1) {
            val best = cands.maxOf { dirCount[dirOf(it)] ?: 0 }
            val top = cands.filter { (dirCount[dirOf(it)] ?: 0) == best }
            if (top.size == 1) return top[0]
            throw PushException(
                "« $base » existe à plusieurs endroits du dépôt :\n" + top.joinToString("\n") { "  • $it" } +
                    "\nIndique le dossier cible, ou ajoute en 1re ligne du fichier : // chemin: dossier/$base"
            )
        }
        if (cands.size == 1) return cands[0]

        // Nouveau fichier : dossier du package s'il existe déjà.
        if (pkgDir != null) {
            val dirs = dirCount.keys.filter { it == pkgDir || it.endsWith("/$pkgDir") }
            val d = dirs.maxByOrNull { dirCount[it] ?: 0 }
            if (d != null) return "$d/$base"
        }
        return null
    }
}
