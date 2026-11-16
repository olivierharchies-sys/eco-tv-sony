package com.example.braviaeco

/** Les 4 modes d'économie d'énergie exposés par l'API Bravia de Sony. */
enum class EcoMode(val api: String, val label: String) {
    OFF("off", "Off"),
    LOW("low", "Bas"),
    HIGH("high", "Haut"),
    PICTURE_OFF("pictureOff", "Image off");

    companion object {
        fun fromApi(value: String?): EcoMode? = values().firstOrNull { it.api == value }
    }
}

/** Une entrée externe de la TV (HDMI 1, HDMI 2, composite…). */
data class TvInput(
    val uri: String,
    val title: String,
    val label: String,
    val connected: Boolean
) {
    val display: String
        get() = if (label.isNotBlank() && !label.equals(title, ignoreCase = true)) "$title · $label" else title
}

/** Une application installée sur la TV (navigateur, Netflix, YouTube…). */
data class TvApp(val title: String, val uri: String, val icon: String = "")

/** Contenu en cours sur la TV (chaîne du tuner : numéro, nom et programme). */
data class PlayingContent(val uri: String, val title: String, val program: String, val number: String)

/** Erreur renvoyée par l'API de la TV, avec son code (7 = état illégal, p. ex. aucune source ou aucun clavier à l'écran). */
class TvApiException(val code: Int, message: String) : IllegalStateException(message)

/** Le clavier système de la TV n'est pas affiché : la TV refuse le texte envoyé d'un bloc. */
class NoTextFieldException : IllegalStateException("Aucun champ de saisie ouvert sur la TV")
