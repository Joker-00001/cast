# 🎮 Xbox Video Caster (Android)

Application mobile Android légère, rapide et sans publicité permettant d'extraire n'importe quel flux vidéo (sites de streaming type Anime-Sama, Vidmoly, Sibnet, Sendvid ou YouTube) et de le diffuser directement sur votre console **Xbox (One / Series S / Series X)** sur le réseau local Wi-Fi via UPnP / DLNA AVTransport.

---

## 🚀 Fonctionnalités Clés

1. **Diffusion DLNA / UPnP AV native** :
   - Cible le port standard UPnP Xbox (`10243`) et le service `/upnp/control/rendertransport1`.
   - Commandes SOAP automatiques : `SetAVTransportURI` puis `Play` (avec DIDL-Lite metadata).
   - Contrôle d'arrêt avec bouton Stop (`Stop`).

2. **Extraction intelligente sans pubs (Anti-Ad Sniffer)** :
   - WebView invisible avec interception réseau `shouldInterceptRequest`.
   - Liste noire des régies publicitaires et popups intrusives.
   - Détection des flux bruts `.mp4`, `.m3u8` (HLS) et formats directs.

3. **Compatibilité YouTube directe** :
   - Résolution instantanée de l'ID vidéo et interrogation d'APIs publiques (Invidious / Piped) pour obtenir le stream brut direct sans restrictions.

4. **Intégration au menu Partager d'Android** :
   - Déclaration de l'intent-filter `android.intent.action.SEND` (`text/plain`).
   - Depuis Chrome, Firefox, Brave ou l'application YouTube, touchez **Partager > Xbox Video Caster** pour déclencher le cast immédiatement.

5. **CI/CD GitHub Actions clé en main** :
   - Fichier `.github/workflows/build.yml` prêt à l'emploi.
   - Compile automatiquement l'APK debug lors de chaque `git push` et met le fichier `.apk` à disposition dans les Artifacts GitHub.

---

## 🛠️ Structure du Projet

```text
XboxVideoCaster/
├── .github/workflows/
│   └── build.yml               # Pipeline CI/CD GitHub Actions
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml # Permissions réseau & intent-filter de partage
│   │   ├── java/com/xboxvideocaster/
│   │   │   ├── dlna/
│   │   │   │   └── DlnaController.kt       # Envoi SOAP UPnP SetAVTransportURI & Play
│   │   │   ├── extractor/
│   │   │   │   ├── VideoStreamSniffer.kt   # Sniffer WebView avec bloqueur de pubs
│   │   │   │   └── YouTubeExtractor.kt     # Extracteur direct flux YouTube
│   │   │   └── MainActivity.kt             # UI, gestion intents & orchestration
│   │   └── res/
│   │       ├── layout/activity_main.xml    # UI Material 3 sombre & sobre
│   │       ├── values/{strings,colors,themes}.xml
│   │       └── drawable/bg_input_field.xml
│   └── build.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 📦 Compilation & Installation

### Option 1 : Via GitHub Actions (Automatique sans installer Android Studio)
1. Poussez ce dossier sur un dépôt GitHub (`git init`, `git add .`, `git commit -m "Init"`, `git push`).
2. Allez dans l'onglet **Actions** de votre dépôt GitHub.
3. Le workflow `Build & Package APK` s'exécute automatiquement.
4. Téléchargez l'archive **XboxVideoCaster-debug-apk** dans la section *Artifacts* et installez l'APK sur votre smartphone Android.

### Option 2 : En local avec Android Studio
1. Ouvrez Android Studio.
2. Choisissez **Open** et sélectionnez le dossier `XboxVideoCaster`.
3. Laissez Gradle synchroniser les dépendances.
4. Branchez votre smartphone en mode Débogage USB ou lancez un émulateur, puis cliquez sur **Run (Shift + F10)**.

---

## 🎮 Configuration sur la Xbox
Assurez-vous que votre Xbox autorise la diffusion sur le réseau local :
- Allez dans **Paramètres > Appareils et connexions > Fonctionnalités à distance / Lecteur multimédia**.
- Vérifiez que la console est connectée au même réseau Wi-Fi / LAN que votre smartphone.
- L'adresse IP locale de la console (ex: `192.168.1.52`) peut être consultée dans **Paramètres > Général > Paramètres réseau > Paramètres avancés**.
