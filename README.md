# Éco TV Sony : widget Android pour la Sony KD-65XF9005

Application + widget d'écran d'accueil pour changer le mode d'économie d'énergie de la TV
depuis le smartphone : **Off, Bas, Haut, Image off**.
Elle utilise l'API réseau Bravia de Sony (Wi-Fi local), sans ADB ni droits spéciaux.

## 1. Préparer la TV

Sur la TV : **Réglages > Réseau > Configuration de l'IP distante** (le chemin exact varie un peu selon la version) :

1. **Authentification** : choisir *Authentification simple et clé pré-partagée*.
2. **Clé pré-partagée** : définir une clé (par ex. `0000`), la noter.
3. Noter l'**adresse IP** de la TV (Réglages > Réseau > État du réseau).
   Idéal : lui réserver une IP fixe dans le routeur.
4. Vérifier que **Contrôle simple de l'IP** est activé.

## 2. Compiler l'APK

### Option A : GitHub Actions (sans rien installer)

1. Créer un dépôt GitHub (privé ou public) et y envoyer tout le contenu de ce dossier.
2. Onglet **Actions** > workflow **Build APK** (il démarre tout seul au push, ou via *Run workflow*).
3. Quand il est vert, ouvrir l'exécution et télécharger l'artefact **eco-tv-sony-apk** (un zip contenant `app-debug.apk`).

### Option B : Android Studio

1. *File > Open* sur ce dossier. Android Studio génère le wrapper Gradle tout seul.
2. *Build > Build APK(s)*. Le fichier est dans `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Installer et configurer

1. Copier `app-debug.apk` sur le smartphone et l'ouvrir (autoriser « sources inconnues » si demandé).
2. Ouvrir l'app **Éco TV Sony**, saisir l'IP et la clé PSK, **Enregistrer**.
3. Utiliser les boutons de test, puis **Lire le mode actuel**.
4. Appui long sur l'écran d'accueil > **Widgets** > **Éco TV Sony**.

Dans le widget, le bouton vert est le dernier mode connu. Toucher le titre relit le mode réel de la TV.
Le smartphone doit être sur le même réseau Wi-Fi que la TV, et la TV allumée
(ou en veille réseau si l'option est activée).

## Dépannage

- *Accès refusé* : la clé PSK ne correspond pas à celle de la TV.
- *Délai dépassé* : mauvaise IP, TV éteinte ou autre réseau Wi-Fi.
- *Mode inconnu / erreur TV* : le firmware ne supporte pas l'appel. Dis-le-moi avec le message exact.
