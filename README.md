# Éco TV Sony : widget + télécommande Android pour la Sony KD-65XF9005

Application Android qui pilote la TV en Wi-Fi local via l'API réseau Bravia de Sony
(sans ADB ni droits spéciaux) :

- **Widget** d'écran d'accueil : mode d'économie d'énergie **Off, Bas, Haut, Image off**,
  plus un bouton **Télécommande**.
- **Télécommande complète** : allumer / éteindre, **sélection de la source** (liste lue sur la TV :
  HDMI 1 à 4, etc.), navigation, OK, retour, accueil, options, volume, chaînes, lecture,
  touches couleur, pavé numérique, Netflix. Volume, chaînes et flèches se répètent quand on
  reste appuyé.

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
3. Tester les boutons, puis **Ouvrir la télécommande**.
4. Appui long sur l'écran d'accueil > **Widgets** > **Éco TV Sony**.

Dans le widget, le bouton vert est le dernier mode connu. Toucher le titre relit le mode réel de la TV.
Le bouton **Télécommande** ouvre la télécommande.

## Mises à jour

Depuis la version 1.1, l'APK est signé avec une clé fixe (`app/debug.keystore`) : les versions
suivantes s'installent par-dessus, sans désinstaller. **Si tu viens de la version 1.0** (signée avec
une clé aléatoire), désinstalle-la une fois avant d'installer la 1.1, puis ressaisis l'IP et la clé PSK.

La clé du dépôt est une clé de test publique (mot de passe `android`) : suffisant pour un usage
personnel, mais ne publie pas cet APK comme une appli « officielle ».

## Dépannage

- *Accès refusé* : la clé PSK ne correspond pas à celle de la TV.
- *Délai dépassé* : mauvaise IP, TV éteinte ou autre réseau Wi-Fi.
- *Sources indisponibles* : TV éteinte, ou firmware sans cet appel. Les touches **Source TV** (Input)
  de la télécommande restent utilisables.
- *Touche non supportée* : la TV ne connaît pas cette touche. Le nom exact est dans le message.
