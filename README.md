# Éco TV Sony : télécommande + 4 widgets pour la Sony KD-65XF9005

Application Android qui pilote la TV en Wi-Fi local via l'API réseau Bravia de Sony
(sans ADB ni droits spéciaux).

## Télécommande (sur un seul écran)

- **Marche/Arrêt** : un seul bouton rond (vert = allumée, gris = en veille).
- **Source** : un seul bouton qui ouvre la liste lue sur la TV (TV, HDMI 1 à 4, avec le nom donné à chaque entrée).
- **Croix directionnelle** ronde avec OK au centre (appui prolongé = répétition).
- Retour, Accueil, Options.
- Volume et chaînes (double bouton + / −, appui prolongé = répétition) et Muet.
- Lecture : précédent, retour rapide, lecture, pause, avance rapide, suivant.
- **Clavier** : ouvre le clavier du téléphone et envoie le texte tapé dans le champ actif de la TV.
- **Chiffres** : ouvre un pavé numérique.

## Nouveautés de la version 1.3

- **Écran verrouillé en portrait** : plus de basculement automatique.
- **Home appareil** : envoie la touche réglée (défaut `SyncMenu`) à l'appareil HDMI sélectionné via HDMI-CEC ;
  si la source est la TV, c'est le Home de la TV. **Home TV** : le Home de la TV, toujours.
- **Web** : ouvre sur la TV la page et le navigateur choisis dans les réglages de l'appli.
- **Plus** : applications de la TV, toutes les touches, volume précis, mode éco, infos TV, redémarrage.
- **Widget « Éco TV · Télécommande »** : marche/arrêt, source, web, plus, retour, Home appareil, Home TV,
  options, croix directionnelle, volume, muet, chaînes.

## Les widgets

Appui long sur l'écran d'accueil > **Widgets** > **Éco TV Sony** :

1. **Éco TV · Modes** : Off, Bas, Haut, Image off (le bouton vert est le dernier mode connu,
   toucher le titre relit le mode réel) + bouton **Télécommande**.
2. **Éco TV · Marche/Arrêt** : un bouton, vert quand la TV est allumée.
3. **Éco TV · Source** : ouvre la liste des sources, affiche la dernière source choisie.
4. **Éco TV · Volume et chaînes** : volume −, muet, volume +, chaîne −, chaîne +.

## 1. Préparer la TV

Sur la TV : **Réglages > Réseau > Configuration de l'IP distante** (le chemin exact varie un peu selon la version) :

1. **Authentification** : choisir *Authentification simple et clé pré-partagée*.
2. **Clé pré-partagée** : définir une clé (par ex. `0000`), la noter.
3. Noter l'**adresse IP** de la TV (Réglages > Réseau > État du réseau).
   Idéal : lui réserver une IP fixe dans le routeur.
4. Vérifier que **Contrôle simple de l'IP** est activé.
5. Pour pouvoir **allumer** la TV depuis le téléphone, activer aussi le démarrage par le réseau
   (selon le menu : *Démarrage à distance* / *Mise sous tension par le réseau*).

## 2. Compiler l'APK

### Option A : GitHub Actions (sans rien installer)

1. Envoyer tout le contenu de ce dossier dans un dépôt GitHub.
2. Onglet **Actions** > workflow **Build APK** (il démarre tout seul au push, ou via *Run workflow*).
3. Quand il est vert, ouvrir l'exécution, descendre jusqu'à **Artifacts** et télécharger **eco-tv-sony-apk**
   (un zip contenant `app-debug.apk`).

### Option B : Android Studio

1. *File > Open* sur ce dossier. Android Studio génère le wrapper Gradle tout seul.
2. *Build > Build APK(s)*. Le fichier est dans `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Installer et configurer

1. Copier `app-debug.apk` sur le smartphone et l'ouvrir (autoriser « sources inconnues » si demandé ;
   si Play Protect bloque, choisir *Installer quand même*).
2. Ouvrir l'app **Éco TV Sony**, saisir l'IP et la clé PSK, **Enregistrer**.
3. Tester les boutons de mode, puis **Ouvrir la télécommande**.
4. Ajouter les widgets voulus sur l'écran d'accueil.

## Mises à jour

Depuis la version 1.1, l'APK est signé avec une clé fixe (`app/debug.keystore`) : les versions
suivantes s'installent par-dessus, sans désinstaller, et les réglages (IP, clé PSK) sont conservés.

La clé du dépôt est une clé de test publique (mot de passe `android`) : suffisant pour un usage
personnel, mais ne publie pas cet APK comme une appli « officielle ».

## Dépannage

- *Accès refusé* : la clé PSK ne correspond pas à celle de la TV.
- *Délai dépassé* : mauvaise IP, TV éteinte ou autre réseau Wi-Fi.
- *Sources indisponibles* : TV éteinte, ou firmware sans cet appel. La liste propose alors HDMI 1 à 4
  et « Menu des sources de la TV ».
- *Le clavier n'écrit rien* : il faut qu'un champ de texte soit actif à l'écran de la TV.
- *Touche non supportée* : la TV ne connaît pas cette touche. Le nom exact est dans le message.
