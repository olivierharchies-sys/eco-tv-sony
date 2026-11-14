package com.example.gitzippush

import android.app.AlertDialog
import android.view.View
import android.widget.TextView

/**
 * Aide contextuelle : appui long sur un bouton, un interrupteur ou une tuile.
 * Chaque fiche : utilité, effet, risques, avantages, utilisation.
 */
object Help {
    const val HINT = "Appui long sur un bouton : aide (utilité, effets, risques, mode d'emploi)."

    private fun h(u: String, e: String, r: String, a: String, m: String) =
        "🎯 Utilité\n$u\n\n⚙️ Effet\n$e\n\n⚠️ Risques\n$r\n\n✅ Avantages\n$a\n\n👉 Utilisation\n$m"

    private const val NONE = "Aucun."

    private val T: Map<String, String> = linkedMapOf(
        // ---------------- tuiles
        "Envoyer" to h(
            "Envoyer un zip, un dossier ou des fichiers vers un dépôt GitHub.",
            "Ouvre le formulaire d'envoi.", NONE, "Tout se fait depuis le téléphone, sans ordinateur.",
            "Choisis la source, confirme le dépôt proposé, puis « Envoyer sur GitHub »."
        ),
        "APK" to h(
            "Installer la dernière version compilée de l'appli du dépôt choisi.",
            "Attend la compilation GitHub, télécharge l'APK et lance l'installation.",
            "Installe la dernière compilation RÉUSSIE : si la plus récente a échoué, c'est la précédente.",
            "Pas de câble ni d'ordinateur.",
            "Le dépôt utilisé est celui du formulaire Envoyer. Autorise l'installation d'applis la première fois."
        ),
        "Historique" to h(
            "Retrouver les zips envoyés dont la compilation a réussi.",
            "Liste les sauvegardes ; permet de restaurer, partager, parcourir.", NONE,
            "Retour arrière facile vers une version qui compilait.", "Touche une sauvegarde pour ses actions."
        ),
        "Rapports d'erreur" to h(
            "Voir les échecs d'envoi ou de compilation.",
            "Chaque rapport peut être copié (court, moyen, complet) ou ouvert en dialogue avec Claude.",
            "Un rapport complet est long : il consomme beaucoup de tokens.",
            "Diagnostic précis sans copier les journaux à la main.", "« Copier » puis colle dans Claude, ou « Dialogue »."
        ),
        "Nettoyage GitHub" to h(
            "Libérer de la place et du quota sur GitHub.",
            "Supprime compilations, artefacts, caches ou branches selon les cases cochées.",
            "Suppression définitive des éléments choisis (le code n'est jamais touché).",
            "Stockage gratuit préservé (500 Mo), liste des compilations lisible.", "Coche, « Analyser », vérifie, puis « Nettoyer »."
        ),
        "Importer pour Claude" to h(
            "Donner tout un dépôt à Claude en un seul fichier texte.",
            "Télécharge le dépôt et le convertit en Markdown (fichiers texte uniquement).",
            "Un gros dépôt = beaucoup de tokens.", "Claude voit tout le projet d'un coup.",
            "Choisis le dépôt, « Préparer pour Claude », puis copie ou envoie."
        ),
        "Explorer un zip" to h(
            "Lire un zip sans l'extraire, choisir des fichiers à envoyer à Claude.",
            "Ouvre l'explorateur de zip.", NONE, "Envoi ciblé : moins de tokens.", "Ouvre un zip, coche des fichiers, envoie."
        ),
        "Compte" to h(
            "Jeton GitHub, dépôts mémorisés, destinations Claude.", "Ouvre les réglages du compte.", NONE,
            "Tout est chiffré ou local au téléphone.", "Commence par coller ton jeton."
        ),
        "Outils GitHub" to h(
            "Quotas, facturation, stockage, visibilité, transfert, compilation gratuite.",
            "Ouvre le formulaire des outils GitHub.", "Certaines actions sont sensibles (public, transfert) : confirmation demandée.",
            "Tout GitHub utile, sans le site.", "Choisis le dépôt, puis le bouton voulu."
        ),

        // ---------------- formulaire d'envoi
        "Mes dépôts" to h(
            "Choisir un dépôt déjà utilisé.", "Remplit dépôt et branche.", NONE, "Pas de faute de frappe.", "Touche un dépôt de la liste."
        ),
        "Créer le dépôt" to h(
            "Créer sur GitHub le dépôt écrit dans le champ.", "Nouveau dépôt (privé si l'interrupteur est activé), initialisé.",
            "Échoue si le nom existe déjà.", "Plus besoin du site GitHub.", "Écris « pseudo/nouveau-nom », puis touche le bouton."
        ),
        "Dépôt privé" to h(
            "Choisir la visibilité d'un dépôt créé ici.", "Privé : invisible des autres. Public : visible de tous.",
            "Privé : les compilations consomment tes minutes (2000/mois gratuites).", "Public : compilations gratuites.",
            "Laisse activé si le code doit rester secret."
        ),
        "Un zip" to h(
            "Choisir un fichier zip à envoyer.", "Analyse le zip et propose un dépôt (une boîte de dialogue demande confirmation).", NONE,
            "Le plus simple.", "Choisis le zip dans le sélecteur."
        ),
        "Un dossier" to h(
            "Envoyer tout un dossier du téléphone.", "Analyse le dossier et propose un dépôt (confirmation demandée).", "Lecture plus lente qu'un zip.",
            "Pas besoin de zipper.", "Choisis le dossier et autorise l'accès."
        ),
        "Des fichiers" to h(
            "Envoyer quelques fichiers seulement.", "Chaque fichier est placé au bon endroit du dépôt (même nom, en-tête « chemin: »).",
            "Un fichier de même nom à plusieurs endroits peut être mal placé : vérifie le journal.", "Corrections rapides.",
            "Choisis les fichiers ; laisse « Sous-dossier » vide pour le placement automatique."
        ),
        "Dernier zip téléchargé" to h(
            "Prendre le zip le plus récent de Téléchargements.", "Sélectionne ce zip.",
            "Vérifie le nom : ce n'est peut-être pas le bon zip.", "Un geste.", "Touche le bouton juste après le téléchargement."
        ),
        "Autoriser l'accès aux fichiers" to h(
            "Permettre de voir Téléchargements et d'y ranger les zips par dépôt.", "Ouvre le réglage Android.",
            "Donne à l'appli l'accès aux fichiers du téléphone.", "Zip détecté seul et rangé après envoi.", "Active l'accès, puis reviens."
        ),
        "Voir le contenu du zip" to h(
            "Regarder le contenu du zip choisi.", "Ouvre l'explorateur (on peut aussi y envoyer des fichiers à Claude).", NONE,
            "Vérifier avant d'envoyer.", "Touche, puis navigue."
        ),
        "Créer un nouveau dépôt" to h(
            "Afficher la création d'un dépôt GitHub.", "Montre ou cache l'interrupteur « privé » et le bouton de création.", NONE,
            "Formulaire lisible.", "Touche."
        ),
        "Plus d'options" to h(
            "Afficher message du commit, sous-dossier et réglages avancés.", "Montre ou cache ces champs et interrupteurs.", NONE,
            "Formulaire lisible.", "Touche ; appui long sur chaque interrupteur pour son aide."
        ),
        "Copier chaque version compilée" to h(
            "Garder un exemplaire de chaque zip qui compile, rangé par dépôt, dans Documents.",
            "Quand la compilation réussit, le zip envoyé est copié dans Documents/Github/<dépôt>/ ; seuls les 3 plus récents y restent.",
            "Les zips plus anciens de ce dossier sont supprimés.", "Retrouver facilement les dernières versions qui fonctionnent.",
            "Laisse activé."
        ),
        "Ignorer le dossier racine" to h(
            "Retirer le dossier qui enveloppe tout le zip.", "« Projet/app/… » devient « app/… » dans le dépôt.",
            "Désactivé à tort : tout arrive dans un sous-dossier du dépôt.", "Dépôt propre.",
            "Laisse le choix proposé, sauf si le dépôt contient vraiment ce dossier."
        ),
        "Supprimer du dépôt les fichiers absents" to h(
            "Que le dépôt soit l'exact reflet de l'envoi.", "Efface du dépôt tout fichier absent du zip ou du dossier.",
            "Zip incomplet = fichiers supprimés (récupérables dans l'historique Git).", "Plus de doublons ni de vieux fichiers.",
            "Active-le seulement avec un projet COMPLET."
        ),
        "Supprimer du dépôt les fichiers inutiles" to h(
            "Retirer build/, .gradle/, .idea/, local.properties, *.iml du dépôt.", "Ces fichiers sont supprimés au prochain envoi.",
            NONE, "Dépôt léger, compilations plus fiables.", "Laisse activé."
        ),
        "Ignorer les dossiers générés" to h(
            "Ne pas envoyer build/, .gradle/, node_modules…", "Ces dossiers sont exclus de l'envoi.", NONE,
            "Envoi plus rapide.", "Laisse activé."
        ),
        "Ajouter un workflow" to h(
            "Compiler l'APK sur GitHub automatiquement.", "Ajoute .github/workflows/build.yml s'il manque.",
            "Le jeton doit avoir le droit « workflow ».", "APK à chaque envoi.", "Laisse activé pour les projets Android."
        ),
        "Incrémenter versionCode" to h(
            "Pouvoir installer la nouvelle version par-dessus l'ancienne.", "versionCode +1 dans app/build.gradle.",
            NONE, "Pas d'erreur « version plus ancienne ».", "Laisse activé."
        ),
        "Tester la connexion" to h(
            "Vérifier jeton, dépôt et branche sans rien envoyer (bouton « Tester l'accès »).", "Lecture seule.", NONE, "Diagnostic rapide.", "Touche, lis le journal."
        ),
        "Envoyer sur GitHub" to h(
            "Publier la source choisie sur le dépôt.",
            "Un commit ; la compilation démarre ; le zip est rangé dans Téléchargements/Envoi GitHub/<dépôt>/Zips. " +
                "Si la compilation réussit, une copie va dans Documents/Github/<dépôt>/ (3 zips gardés).",
            "Avec « Supprimer les fichiers absents », un zip incomplet efface des fichiers.", "Un seul commit, uniquement les fichiers modifiés.",
            "Vérifie dépôt et branche, puis touche."
        ),
        "Récupérer et installer l'APK" to h(
            "Installer la dernière compilation réussie.", "Télécharge l'artefact, extrait l'APK, lance l'installation.",
            "Si la dernière compilation a échoué, c'est la précédente réussie qui est installée (signalé).",
            "Pas d'ordinateur.", "Touche après un envoi ; valide la fenêtre Android."
        ),
        "Le faire automatiquement" to h(
            "Installer l'APK tout seul après chaque envoi.", "Attend la compilation du commit envoyé, puis installe.",
            "Notification d'installation à chaque envoi.", "Zéro geste.", "Active-le si tu testes souvent."
        ),
        "Voir les Actions" to h("Ouvrir la page des compilations du dépôt.", "Ouvre le navigateur.", NONE, "Suivi détaillé.", "Touche."),
        "Ouvrir le dépôt" to h("Ouvrir le dépôt sur GitHub.", "Ouvre le navigateur.", NONE, "Accès direct.", "Touche."),

        "Options d'envoi" to h("Afficher les réglages avancés de l'envoi.", "Montre ou cache les interrupteurs.", NONE, "Formulaire lisible.", "Touche ; appui long sur chaque interrupteur pour son aide."),
        "Message, sous-dossier, création" to h("Afficher message de commit, sous-dossier et création de dépôt.", "Montre ou cache ces champs.", NONE, "Formulaire lisible.", "Touche."),

        // ---------------- compte
        "Afficher" to h("Voir le jeton saisi.", "Affiche ou masque le texte.", "Quelqu'un peut lire l'écran.", "Vérifier la copie.", "Touche."),
        "Masquer" to h("Cacher le jeton saisi.", "Masque le texte.", NONE, NONE, "Touche."),
        "Créer un jeton" to h(
            "Obtenir un jeton GitHub avec les bons droits.", "Ouvre GitHub, droits pré-cochés (repo, workflow, delete_repo, user).",
            "Le jeton est un mot de passe : ne le partage jamais.", "Rien à chercher dans les menus.",
            "Génère, copie, reviens, « Coller »."
        ),
        "Effacer le jeton" to h("Retirer le jeton du téléphone.", "Supprime le jeton chiffré.", "Plus aucune action GitHub possible.", "Sécurité.", "Touche."),
        "Explications" to h("Comprendre le jeton GitHub.", "Affiche ou masque le guide.", NONE, NONE, "Touche."),
        "Vérifier / Outils" to h("Contrôler le jeton et ouvrir les outils.", "Enregistre le jeton saisi, ouvre Outils GitHub.", NONE, NONE, "Puis « Vérifier »."),
        "Charger depuis GitHub" to h("Remplir la liste « Mes dépôts ».", "Lit la liste de tes dépôts.", NONE, "Listes déroulantes complètes.", "Touche une fois."),
        "Oublier un dépôt…" to h("Retirer un dépôt des listes.", "Le dépôt n'est plus proposé (rien n'est supprimé sur GitHub).", NONE, "Listes claires.", "Choisis le dépôt."),
        "Gérer les destinations" to h("Mémoriser des conversations ou projets Claude.", "Liste, ajoute ou supprime des liens claude.ai.", NONE, "Envoi en deux gestes.", "« Ajouter… », colle le lien."),

        // ---------------- rapports et dialogue
        "Copier" to h(
            "Mettre le texte dans le presse-papiers.", "Copie (sans fichier).", "Un texte très long passe par un fichier partagé.",
            "Coller directement dans Claude.", "Touche, puis colle dans Claude."
        ),
        "Dialogue" to h(
            "Résoudre une erreur avec Claude en dépensant peu de tokens.",
            "Échange guidé : l'appli fournit le rapport court ; Claude demande le reste par commandes @ ; l'appli exécute.",
            "@PATCH / @WRITE modifient le dépôt (un commit) et relancent la compilation.",
            "Seul le nécessaire est envoyé à Claude.", "Copier → coller dans Claude → copier sa réponse → « Coller » → « Exécuter »."
        ),
        "Plus…" to h("Autres actions sur ce rapport.", "Niveaux moyen/complet, relance, suppression.", NONE, NONE, "Touche."),
        "Supprimer tous les rapports" to h("Vider la liste des rapports.", "Efface les rapports du téléphone.", "Définitif.", "Liste propre.", "Touche, confirme."),
        "Coller" to h("Coller le presse-papiers dans le champ.", "Remplace le contenu du champ.", NONE, "Rapide.", "Copie d'abord, puis touche."),
        "Exécuter" to h(
            "Exécuter les commandes @ de la réponse de Claude.", "Lecture du dépôt, ou commit si @PATCH/@WRITE/@DELETE.",
            "Un patch modifie le dépôt (commit visible, réversible).", "Réponse prête à recopier.", "Colle la réponse de Claude, puis touche."
        ),
        "Copier + Claude" to h("Copier et ouvrir l'appli Claude.", "Copie puis ouvre Claude.", NONE, "Un geste de moins.", "Touche, puis colle dans la conversation."),
        "Claude" to h("Ouvrir l'appli Claude.", "Bascule vers Claude.", NONE, NONE, "Touche."),

        // ---------------- nettoyage
        "Analyser" to h("Voir ce qui serait supprimé.", "Lecture seule.", NONE, "Aucune surprise.", "Coche les éléments, touche, lis le résumé."),
        "Nettoyer" to h("Supprimer ce que l'analyse a trouvé.", "Suppression sur GitHub.", "Définitif (le code n'est pas touché).", "Quota libéré.", "Après « Analyser », touche et confirme."),
        "Supprimer un dépôt…" to h(
            "Supprimer définitivement un dépôt GitHub.", "Le dépôt, son code et son historique disparaissent.",
            "IRRÉVERSIBLE. Droit « delete_repo » requis.", "Compte propre.", "Retape le nom exact pour confirmer."
        ),
        "Caches Actions" to h(
            "Libérer l'espace des caches Gradle.", "Supprime les caches de compilation.",
            "Compilation suivante plus lente (1 à 3 min de plus, une fois).", "Stockage libéré, sans effet sur le code ni les APK.",
            "Évite pendant une compilation en cours."
        ),
        "Artefacts" to h(
            "Supprimer les APK stockés par GitHub.", "Les artefacts ne sont plus téléchargeables.",
            "Le bouton APK ne pourra plus récupérer ces versions.", "Stockage libéré.", "Garde au moins la dernière compilation."
        ),
        "Compilations échouées" to h("Effacer les échecs de la liste Actions.", "Supprime ces compilations et leurs journaux.", "Journaux perdus.", "Liste lisible.", "Après avoir corrigé l'erreur."),
        "Anciennes compilations" to h("Ne garder que les plus récentes.", "Supprime les compilations au-delà du nombre gardé.", "Journaux anciens perdus.", "Liste lisible.", "Choisis combien garder."),
        "Branches déjà fusionnées" to h("Supprimer les branches intégrées.", "Les branches fusionnées sont effacées.", "Récupérables quelques jours sur GitHub.", "Dépôt clair.", "Coche, analyse, nettoie."),

        // ---------------- outils GitHub
        "Vérifier" to h("Contrôler le jeton.", "Affiche compte, offre, expiration, droits manquants.", NONE, "Explique les erreurs 403.", "Touche."),
        "Créer (classique)" to h("Créer un jeton classique (ghp_).", "Ouvre GitHub, droits pré-cochés.", "C'est un mot de passe.", "Marche partout.", "Génère, copie, colle dans Compte."),
        "Créer (fin)" to h("Créer un jeton fin (github_pat_).", "Ouvre GitHub.", "Droits à choisir un par un.", "Accès limité à certains dépôts.", "Suis les Explications."),
        "Quotas" to h("Voir ce qu'il te reste.", "Limites d'API, minutes Actions du mois.", NONE, "Éviter les blocages.", "Touche."),
        "Facturation" to h("Voir la consommation et le coût du mois.", "Détail par produit et par dépôt.", "Droit « user » requis.", "Pas de surprise.", "Touche."),
        "Page facturation" to h("Ouvrir la facturation GitHub.", "Ouvre le navigateur.", NONE, NONE, "Touche."),
        "Limite de dépenses" to h("Fixer un budget maximum.", "Ouvre la page des budgets GitHub.", NONE, "Jamais de facture imprévue.", "Mets 0 $ pour tout bloquer."),
        "Stockage" to h("Voir l'espace Actions du dépôt.", "Artefacts, caches, rétention.", NONE, "Savoir quoi nettoyer.", "Touche."),
        "Rétention 1 j" to h(
            "Faire expirer vite APK et journaux.", "Les nouveaux artefacts et journaux expirent après 1 jour.",
            "Récupère l'APK le jour même.", "Stockage quasi nul.", "Touche une fois par dépôt."
        ),
        "Lancer compilation" to h("Compiler sans rien envoyer.", "Lance le workflow (ou relance le dernier).", "Consomme des minutes (dépôt privé).", "Recompiler après un échec réseau.", "Touche."),
        "Annuler en cours" to h("Arrêter les compilations en cours.", "Annule les compilations en cours ou en attente.", NONE, "Minutes économisées.", "Touche."),
        "Visibilité ?" to h("Savoir si le dépôt est public.", "Lecture seule.", NONE, NONE, "Touche."),
        "Rendre public" to h(
            "Compiler gratuitement.", "Le dépôt devient visible de tous.", "Code, historique et secrets éventuels exposés.",
            "Minutes Actions illimitées.", "Vérifie qu'aucun mot de passe ni clé n'est dans le dépôt."
        ),
        "Rendre privé" to h("Masquer le dépôt.", "Le dépôt n'est plus visible.", "Compilations à nouveau décomptées.", "Code protégé.", "Touche."),
        "Transférer le dépôt" to h(
            "Donner le dépôt à un autre compte ou une organisation.", "Le dépôt change de propriétaire et d'adresse.",
            "Tu peux perdre l'accès ; les liens mémorisés changent.", "Regrouper les projets.",
            "Écris le nouveau propriétaire, touche, confirme. Un autre utilisateur doit accepter par e-mail."
        ),
        "Copier + compiler + APK" to h(
            "Compiler sans consommer de minutes.", "Copie le dépôt privé vers un dépôt public « -build », compile, installe l'APK.",
            "La copie publique expose le code.", "Gratuit, le dépôt d'origine reste privé.",
            "Choisis le dépôt, laisse le nom proposé, touche, confirme."
        ),
        "Installer l'APK à la fin" to h("Installer automatiquement.", "Lance l'installation après la compilation.", NONE, "Zéro geste.", "Laisse activé."),
        "Annuler" to h("Arrêter l'opération en cours.", "Interrompt dès que possible.", "Peut laisser une opération à moitié faite.", NONE, "Touche."),

        // ---------------- divers
        "Envoyer ce fichier à Claude" to h("Transmettre ce fichier à Claude.", "Copie le texte ou prépare le fichier, ouvre Claude.", NONE, "Ciblé : peu de tokens.", "Choisis la destination."),
        "Envoyer à Claude" to h("Transmettre la sélection à Claude.", "Copie le texte ou prépare un fichier, ouvre Claude.", "Gros contenu = beaucoup de tokens.", "Ciblé.", "Choisis la destination."),
        "Préparer pour Claude" to h("Convertir le dépôt en un texte pour Claude.", "Télécharge et assemble les fichiers texte.", "Volumineux.", "Contexte complet.", "Choisis le dépôt, touche."),
        "Vérifier les compilations en attente" to h("Mettre à jour l'historique.", "Interroge GitHub sur les compilations en cours.", NONE, NONE, "Touche.")
    )

    /** Fiche d'aide d'un libellé (correspondance exacte, sinon par début). */
    fun of(label: String): String? {
        val l = label.trim()
        T[l]?.let { return it }
        return T.entries.filter { l.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    }

    /** Appui long : affiche la fiche du libellé courant du bouton. */
    fun attach(v: TextView) {
        v.isLongClickable = true
        v.setOnLongClickListener { show(v, v.text.toString()) }
    }

    fun attach(v: View, label: String) {
        v.isLongClickable = true
        v.setOnLongClickListener { show(v, label) }
    }

    private fun show(v: View, label: String): Boolean {
        val text = of(label) ?: return false
        return try {
            AlertDialog.Builder(v.context).setTitle(label.trim()).setMessage(text).setPositiveButton("OK", null).show()
            true
        } catch (e: Exception) {
            false
        }
    }
}
