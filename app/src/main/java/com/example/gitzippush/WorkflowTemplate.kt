package com.example.gitzippush

/**
 * Génère un workflow GitHub Actions « Build APK » pour un projet Android Gradle qui n'en a pas encore,
 * afin que « tout projet Android complet » soit compilable par GitHub après l'envoi.
 */
object WorkflowTemplate {
    const val PATH = ".github/workflows/build.yml"
    const val WRAPPER_PROPS = "gradle/wrapper/gradle-wrapper.properties"
    const val WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar"

    fun hasWorkflow(paths: Collection<String>): Boolean = paths.any { it.startsWith(".github/workflows/") }

    /** Projet Android Gradle : settings.gradle à la racine + un AndroidManifest dans src/main. */
    fun isAndroidProject(paths: Collection<String>): Boolean =
        paths.any { it == "settings.gradle" || it == "settings.gradle.kts" } &&
            paths.any { it.endsWith("src/main/AndroidManifest.xml") }

    fun gradleVersion(props: String?): String? {
        if (props == null) return null
        val m = Regex("gradle-([0-9][0-9A-Za-z.\\-]*?)-(bin|all)\\.zip").find(props) ?: return null
        return m.groupValues[1]
    }

    fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return s.ifEmpty { "app" }
    }

    fun build(repoName: String, paths: Collection<String>, wrapperProps: String?): String {
        val useWrapper = "gradlew" in paths && WRAPPER_JAR in paths
        val version = gradleVersion(wrapperProps) ?: "8.7"
        val sb = StringBuilder()
        sb.append("name: Build APK\n\n")
        sb.append("on:\n  push:\n  workflow_dispatch:\n\n")
        sb.append("jobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n")
        sb.append("      - uses: actions/checkout@v4\n\n")
        sb.append("      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: 17\n\n")
        if (useWrapper) {
            sb.append("      - name: Build debug APK\n        run: |\n          chmod +x ./gradlew\n          ./gradlew assembleDebug --no-daemon\n\n")
        } else {
            sb.append("      - uses: gradle/actions/setup-gradle@v4\n        with:\n          gradle-version: $version\n\n")
            sb.append("      - name: Build debug APK\n        run: gradle assembleDebug --no-daemon\n\n")
        }
        sb.append("      - uses: actions/upload-artifact@v4\n        with:\n")
        sb.append("          name: ${slug(repoName)}-apk\n")
        sb.append("          path: \"**/build/outputs/apk/debug/*.apk\"\n")
        sb.append("          if-no-files-found: error\n")
        sb.append("          retention-days: 1\n")
        return sb.toString()
    }

    /** Ajoute « retention-days: 1 » à chaque upload-artifact qui n'en a pas. */
    fun withRetention(text: String): String {
        val out = ArrayList<String>()
        var state = 0            // 0 rien, 1 upload-artifact vu, 2 dans son bloc with:
        var keyIndent: String? = null
        var has = false
        fun closeBlock() {
            if (state == 2 && keyIndent != null && !has) {
                var i = out.size
                while (i > 0 && out[i - 1].isBlank()) i--
                out.add(i, keyIndent + "retention-days: 1")
            }
            state = 0; keyIndent = null; has = false
        }
        for (line in text.split("\n")) {
            val lead = line.takeWhile { it == ' ' }
            if (state == 2 && line.isNotBlank() && keyIndent != null && lead.length < keyIndent!!.length) closeBlock()
            if (line.contains("actions/upload-artifact")) {
                closeBlock(); state = 1
            } else if (state == 1 && line.trim().startsWith("with:")) {
                state = 2
            } else if (state == 2 && line.isNotBlank()) {
                if (keyIndent == null) keyIndent = lead
                if (line.trimStart().startsWith("retention-days:")) has = true
            }
            out.add(line)
        }
        closeBlock()
        return out.joinToString("\n")
    }
}
