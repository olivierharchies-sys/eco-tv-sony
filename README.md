# Envoi GitHub : envoyer un zip dans un dépôt GitHub depuis le téléphone

Application Android qui fait la même chose que `unzip` + `git add -A` + `git commit` + `git push` dans Termux :
elle prend un fichier zip, compare son contenu avec le dépôt GitHub, et crée **un seul commit** avec
les fichiers nouveaux ou modifiés. Elle parle uniquement à `api.github.com`.

## Ce qu'elle fait

- Choisir un zip (bouton **Choisir un zip**), ou le **partager** vers l'appli depuis le gestionnaire de fichiers.
- Ignorer automatiquement le dossier racine du zip (ex. `BraviaEco/…`) : option activée par défaut quand le zip en a un.
- N'envoyer que les fichiers qui ont changé (comparaison des empreintes Git), puis créer un seul commit.
- Si rien n'a changé : « Rien à envoyer ».
- Option **Supprimer du dépôt les fichiers absents du zip** (désactivée par défaut, comme avec `cp -r`).
- Dépôt vide ou branche inexistante : créés automatiquement.
- Boutons **Voir les Actions** et **Ouvrir le dépôt** après l'envoi.
- **Tester la connexion** : vérifie le jeton, ses droits (`repo`, `workflow`) et l'accès au dépôt.
- **Créer le dépôt** : crée le dépôt `pseudo/depot` s'il n'existe pas (privé par défaut, avec un fichier README initial).
  Il faut un jeton classique avec le droit `repo`. Si le dépôt existe déjà, rien n'est modifié.

## Nouveautés de la version 1.11

- **Le dépôt deviné n'est plus rempli d'office** : une boîte de dialogue demande « Le fichier X est-il bien lié au dépôt Y ? »
  (avec la raison : même nom, nom proche, déjà envoyé sous ce nom, fichiers déjà présents). Réponses : *Oui, c'est lui*,
  *Non, un autre* (ouvre « Mes dépôts ») ou *Plus tard*. Si le dépôt n'a pas été confirmé au moment d'envoyer, une dernière
  confirmation est demandée.
- **Formulaire Envoyer réorganisé en étapes** : 1. Ce qu'on envoie (résumé de la source, dossier racine, choix zip / dossier /
  fichiers, dernier zip téléchargé) ; 2. Où on l'envoie (état « confirmé / à confirmer », dépôt, branche, test d'accès,
  création d'un dépôt) ; 3. Options (suppression des fichiers absents, puis « Plus d'options ») ; 4. Envoyer (récapitulatif
  source → dépôt · branche, avec les avertissements utiles). Les trois boutons de choix de la source tiennent sur une ligne.
- **Copie dans `Documents/Github/<nom du dépôt>/`** quand la compilation réussit : le zip envoyé y est copié et seuls les
  **3 zips les plus récents** du dossier sont gardés. Réglage dans Historique (activé par défaut). Fonctionne avec ou sans
  « Accès à tous les fichiers » (Android 10 et plus).

## Nouveautés de la version 1.7

- **Interface en tuiles** : Envoyer, APK, Historique, Rapports d'erreur, Nettoyage GitHub, Importer, Explorer, Compte.
  « ← Accueil » ou Retour revient aux tuiles.
- **Rapport d'erreur complet** (fichier `.md`, tuile Rapports, copie dans `Téléchargements/Envoi GitHub/Rapports d'erreur`) :
  étapes, annotations, lignes d'erreur, fichiers cités (avec numéros de ligne), modifications depuis la dernière
  compilation réussie (diff), fichiers Gradle/manifeste, workflow, arborescence, journal complet.
  Trois niveaux pour économiser les tokens : **COURT** (≈ 3–5 k tokens, copié automatiquement : erreurs, extraits
  ±8 lignes, fichiers modifiés), **MOYEN** (≈ 15 k : + fin du journal, diff, Gradle, manifeste, workflow),
  **COMPLET** (tout). Chaque niveau contient les consignes pour que Claude demande précisément le niveau
  supérieur ou un fichier (« Envoie le rapport MOYEN », « Envoie le fichier <chemin> »). « Relancer les jobs en échec ».
- **Zip d'origine supprimé du téléphone** quand sa compilation échoue (réglage dans Historique, activé par défaut).
  Seules les versions compilées avec succès restent dans l'historique.
- **Nettoyage GitHub** : compilations échouées/anciennes (N gardées), artefacts, caches Actions, branches fusionnées ;
  un dépôt ou tous les dépôts mémorisés ; analyse puis confirmation.
  Option : nettoyage automatique chaque semaine. Suppression d'un dépôt entier (nom à retaper, droit `delete_repo`).
- **versionCode incrémenté automatiquement** à chaque envoi (si le zip n'a pas déjà une valeur plus haute que le dépôt,
  et seulement si d'autres fichiers changent).
- **Notification de fin de compilation** : « Installer l'APK » si réussite ; « Envoyer le rapport à Claude » et
  « Relancer » si échec.
- **Restauration** : Historique > Plus… > « Renvoyer cette version sur GitHub » (exacte ou simple remplacement).
- **Vérification du zip** avant envoi : settings.gradle, app/build.gradle, manifeste, workflow, doublons, `src/` à la racine.
  Confirmation demandée si « Supprimer les fichiers absents » est activé avec un zip incomplet.

## Nouveautés de la version 1.6

- **Fichier seul placé au bon endroit** : partage un fichier (ex. fichier donné par Claude) vers l'appli, sans dossier cible.
  Emplacement choisi : (1) repère en tête de fichier `// chemin: app/src/.../X.kt` (aussi `#`, `<!-- -->`, `path:`) ;
  (2) sinon fichier du même nom dans le dépôt (départage par `package`, puis dossier le plus fourni) ;
  (3) sinon dossier du `package` ; sinon racine. « (1) » ajouté au nom par Android est ignoré.
  Jamais de suppression ni de workflow automatique dans ce mode.

## Nouveautés de la version 1.3

- **Sauvegarde à chaque compilation réussie** : à chaque envoi qui crée un commit, une copie du zip (ou du dossier/des
  fichiers, remis en zip) est mise de côté. L'appli surveille la compilation GitHub Actions de ce commit (25 minutes
  au plus, visible dans le widget) : si elle réussit, la copie est gardée ; si elle échoue, elle est supprimée.
  Les copies encore en attente sont revérifiées à chaque ouverture de l'appli (ou bouton « Vérifier les compilations
  en attente »). La récupération de l'APK valide aussi la sauvegarde.
  Réglages : nombre de sauvegardes gardées (30 par défaut) et copie dans `Téléchargements/Envoi GitHub/Compilations réussies`.
- **Onglet Fichiers** : liste des sauvegardes ; « Parcourir » ouvre l'explorateur. On peut aussi parcourir n'importe
  quel zip du téléphone, ou le zip choisi dans l'onglet Envoyer.
- **Explorateur** : arborescence, recherche par **nom** (morceau de chemin ou motif `*.kt`, instantanée) ou dans le
  **contenu** (toutes les occurrences, avec numéros de ligne et extraits), aperçu du texte avec surlignage et
  « Occurrence suivante ». Cases à cocher sur les fichiers ; appui long sur un dossier = tout son contenu ;
  « Tout cocher » coche les résultats affichés.
- **Envoyer à Claude** (sélection, fichier seul, extrait de texte sélectionné, projet complet, import de dépôt) :
  - **Nouvelle conversation** : partage direct vers l'appli Claude ;
  - **Conversation existante** (y compris une conversation d'un projet) ;
  - **Nouvelle conversation dans un projet** ;
  - **Connaissances (contexte) d'un projet** ;
  - **Contexte de la conversation ouverte** : copie dans le presse-papiers ;
  - ou une autre appli.

  Android ne permet pas de déposer un fichier directement dans une conversation ou un projet précis : pour ces cibles,
  l'appli copie le texte dans le presse-papiers, enregistre le `.md` dans `Téléchargements/Envoi GitHub/Pour Claude`,
  puis ouvre la conversation ou le projet dans Claude ; il reste à coller (ou à joindre le fichier avec +).
  Les liens des conversations et projets (`https://claude.ai/chat/…`, `https://claude.ai/project/…`) se mémorisent
  dans **Compte > Destinations Claude** ou directement au moment de l'envoi.

## Nouveautés de la version 1.2

- **Trois onglets** : Envoyer, Importer, Compte.
- **Dépôts mémorisés** : chaque dépôt utilisé avec succès est retenu (bouton « Mes dépôts », « Charger depuis GitHub » pour ajouter ceux de ton compte, « Oublier un dépôt »).
- **Dernier zip téléchargé** : l'appli propose d'office le zip le plus récent du dossier Téléchargements
  (il faut autoriser une fois « Accès à tous les fichiers », Android 11 et plus).
- **Dépôt deviné** : quand tu choisis un zip, l'appli propose le dépôt d'après (1) les envois précédents du même nom
  (« Appli-v3.zip » = « Appli-v2.zip »), (2) la ressemblance du nom, (3) les fichiers déjà présents dans tes dépôts récents.
- **Autres sources** : un dossier complet, ou plusieurs fichiers, en plus du zip ; sous-dossier de destination facultatif ;
  dossiers générés (build, .gradle, node_modules…) ignorés. Limite : 2500 fichiers par dossier.
- **Projet Android complet** : si le projet n'a pas de workflow GitHub Actions, un `build.yml` est ajouté automatiquement
  (réglage désactivable) ; il utilise `gradlew` s'il est présent, sinon Gradle installé par GitHub.
- **Importer pour Claude** : télécharge un dépôt et le met en Markdown (arborescence + code), découpé en parties de
  300 000 caractères au plus (8 parties maximum). Partage vers Claude, copie du texte, ou partage du zip brut.
- **Widget « Suivi des tâches »** et notification de progression (service au premier plan) pendant les opérations longues.

## Récupérer et installer l'APK

Bouton **Récupérer et installer l'APK** (carte « Application (APK) ») :
1. attend la fin de la compilation GitHub Actions si elle est en cours (15 minutes maximum, bouton **Annuler**) ;
2. télécharge l'artefact de la dernière compilation réussie et en extrait le fichier `.apk` ;
3. ouvre l'installation d'Android, que tu confirmes (Play Protect peut demander « Installer quand même »).

L'option **Le faire automatiquement après chaque envoi** enchaîne : envoi du zip, attente de la compilation de CE commit,
téléchargement, installation.

La première fois, Android demande d'autoriser « Envoi GitHub » à installer des applis : l'appli ouvre l'écran
correspondant, active l'autorisation puis reviens et relance. Le dépôt doit avoir un workflow GitHub Actions
qui publie un artefact contenant un `.apk` (c'est le cas des projets Éco TV Sony et Envoi GitHub).

## Le jeton GitHub

Bouton **Créer un jeton** : ouvre GitHub avec les droits `repo` et `workflow` déjà cochés.
Choisis une durée, génère le jeton, copie-le avec l'icône de copie, puis dans l'appli appuie sur **Coller**.
L'appli retire les espaces et retours à la ligne invisibles et affiche la longueur (40 pour un jeton `ghp_…`).

Le jeton est chiffré avec une clé de l'Android Keystore, n'est pas inclus dans les sauvegardes Android et
n'est envoyé qu'à GitHub. **Effacer le jeton** le supprime du téléphone.

Jeton « fine-grained » : donne-lui l'accès au dépôt avec *Contents : Read and write* (et *Workflows : Read and write*
si le zip contient `.github/workflows`).

## Compiler l'APK

Comme pour Éco TV Sony : envoyer ce dossier dans un dépôt GitHub (la première fois, par exemple avec Termux),
puis onglet **Actions** > **Build APK** > artefact **envoi-github-apk** (zip contenant `app-debug.apk`).
Ou Android Studio : *File > Open*, puis *Build > Build APK(s)*.

L'APK est signé avec une clé fixe (`app/debug.keystore`) : les versions suivantes s'installent par-dessus.

## Limites

- Formats tar, 7z, rar non pris en charge (zip uniquement).
- Fichiers de plus de 95 Mo refusés (GitHub limite à 100 Mo). Pas de Git LFS.
- Les droits d'exécution (`chmod +x`) des nouveaux fichiers ne sont pas gérés : un fichier déjà présent garde le sien.
- Les sous-modules Git ne sont pas pris en charge ; les dossiers `.git`, `__MACOSX` et les `.DS_Store` du zip sont ignorés.
- GitHub limite le nombre de créations de contenu par minute : l'appli attend et réessaie toute seule si besoin.
- Un envoi fait une seule fois ne peut pas être « annulé » depuis l'appli ; utilise GitHub (Revert) si besoin.
