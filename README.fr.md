# MrCuisineVibeConnect

🇬🇧 **[English version](README.md)**

**Toque**, une interface moderne et rapide pour les robots **Monsieur Cuisine** de Lidl : le
**Connect**, et le **Smart** en test. Elle remplace l'appli d'origine sur la tablette du robot tout en
gardant **les recettes Lidl**.

🌐 **[toqueapp.org](https://toqueapp.org)**, le site communautaire de Toque : partagez vos recettes,
trouvez celles des autres et envoyez-les sur votre robot.

## Robots pris en charge

| Robot | Étiquette sous le robot | État | Installation |
|---|---|---|---|
| Monsieur Cuisine **Connect** | SKMC 1200 (A1, B2, C3) | Pris en charge | [Agent IA](AGENTS.md) ou [guide d'installation](docs/INSTALLATION.fr.md), puis `Toque-x.y.z.apk` |
| Monsieur Cuisine **Connect** | SKMC 1100 A1 | Pris en charge | Pareil, avec une étape de plus, `own-twrp` : TWRP reconstruit autour du noyau du robot ([#10](https://github.com/Wuki60/MrCuisineVibeConnect/issues/10)) |
| Monsieur Cuisine **Smart** | SKMS 1200 | **En test** : essayé par des propriétaires de Smart, retours dans [#59](https://github.com/Wuki60/MrCuisineVibeConnect/issues/59) | [Guide du Smart](docs/SMART.md), puis `Toque-smart-x.y.z.apk` |

Chaque [version](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest) a deux APK faits du
même code : **`Toque-x.y.z.apk` pour le Connect** et **`Toque-smart-x.y.z.apk` pour le Smart** (son
Android 8.1 a besoin du mode sûr de la machine virtuelle, qui ralentirait le Connect). Une fois Toque
installé, *Réglages › Mises à jour* prend tout seul le bon. Au premier démarrage, Toque reconnaît le
robot sans rien envoyer à sa base moteur et demande de le confirmer.

> Projet indépendant, non affilié à Lidl, SilverCrest ni Hoyer. Le pilotage du moteur repose
> sur une analyse publique du protocole. Depuis octobre 2026, Toque tourne sur des SKMC 1200
> (révisions A1, B2 et C3) en France et en Espagne, et sur des SKMC 1100 A1 (TWRP reconstruit
> autour de leur propre noyau, ce que le script d'installation fait de lui-même, voir #10). La prise
> en charge du Smart vient des portages de Flav974 et de Tidusneo24150 ; le projet n'a pas de Smart.
> À utiliser à vos risques, en suivant la procédure de premier essai.
>
> L'interface existe en **français**, **anglais**, **allemand**, **espagnol**, **catalan** et
> **ukrainien** (*Réglages › Langue et heure*).

## Le site communautaire : [toqueapp.org](https://toqueapp.org)

Le site de recettes de Toque, gratuit et sans publicité, en français, anglais, allemand, espagnol,
catalan et ukrainien.

- **Parcourez et cherchez** les recettes partagées par les utilisateurs de Toque, avec photos, notes
  et avis ; filtres par robot (Connect, Smart), langue et catégorie.
- **Publiez vos recettes** sur le site ou directement depuis le robot. Les réglages de chaque étape
  (durée, température, vitesse) sont vérifiés avec les mêmes règles de sécurité que sur le robot.
- **Envoyez-les sur votre robot** : les recettes de la communauté arrivent sur le robot sans aucun
  compte (*Réglages › Recettes › Communauté*). Avec un compte Toque gratuit et facultatif
  (à relier sur la même page), vos favoris et vos recettes vous suivent sur le robot.
- **Traduisez** les recettes des autres dans votre langue, **abonnez-vous** aux cuisiniers que vous
  aimez, et signalez ce qui n'a rien à y faire.
- Votre compte reste privé : le profil public ne montre que votre pseudo et vos recettes publiées, et
  le robot garde une clé que vous pouvez révoquer, jamais votre mot de passe.

Une question, ou un robot que vous aimeriez voir pris en charge : [page Contact](https://toqueapp.org/contact.php).
Si Toque vous rend service, vous pouvez [soutenir le projet](https://toqueapp.org/donate.php).

## Installer avec un agent IA (Connect)

Le plus simple : confiez l'installation à un agent IA qui peut piloter votre PC Windows ou votre Mac
(Claude, ChatGPT, Copilot…). Donnez-lui ce message :

> Installe Toque sur mon Monsieur Cuisine Connect en suivant le fichier AGENTS.md de
> https://github.com/Wuki60/MrCuisineVibeConnect

Le fichier [AGENTS.md](AGENTS.md) lui décrit toute la procédure, et le script
[`tools/setup/toque-setup.ps1`](tools/setup/toque-setup.ps1) (Windows) ou
[`tools/setup/toque-setup.sh`](tools/setup/toque-setup.sh) (macOS) fait le travail étape par étape
(téléchargements vérifiés, pilote sous Windows, sauvegarde, root, installation, ADB par Wi-Fi).
L'agent ne vous demande que ce qu'il ne peut pas faire lui-même :

- avoir un câble **USB-A mâle ↔ USB-A mâle** (plus un adaptateur USB-C → USB-A si votre Mac n'a que
  des ports USB-C) et un petit tournevis Torx ;
- dévisser la trappe sous le robot, brancher le câble sur le port **« Android USB »**, éteindre et
  rallumer le robot quand il le dit ;
- répondre aux fenêtres de sécurité de Windows (ou aux demandes Homebrew / accessoire sur Mac) ;
- appuyer une fois sur **Autoriser** sur l'écran du robot.

Comptez 1 à 2 heures. Sans agent : [docs/INSTALLATION.fr.md](docs/INSTALLATION.fr.md) (Windows) ou
[docs/MACOS.md](docs/MACOS.md) (macOS).

## Installer depuis un Mac (Connect)

Toque s'installe aussi depuis un Mac, avec les mêmes étapes et les mêmes
vérifications que sous Windows. Pas de pilote ni de SP Flash Tool : [mtkclient](https://github.com/bkerler/mtkclient)
fait les deux étapes qui demandent le mode téléchargement MediaTek, et TWRP écrit le reste.

1. Installez [Homebrew](https://brew.sh), puis `brew install python libusb` et `xcode-select --install`.
2. Prenez un câble **USB-A ↔ USB-A** ; sur un Mac qui n'a que des ports USB-C, ajoutez un
   **adaptateur USB-C → USB-A**.
3. Depuis le dossier du dépôt, lancez les étapes dans l'ordre, chacune quand la précédente le dit :
   `bash tools/setup/toque-setup.sh prepare`, puis `backup`, `flash-twrp` (sur un SKMC 1100 A1,
   `own-twrp` d'abord), `root`, `flash-root`, `android`, `install`, et si vous voulez `wifi`.
   `bash tools/setup/toque-setup.sh status` dit où vous en êtes.
4. **Gardez une copie de `~/Toque/backup/`** (clé USB ou cloud) : c'est votre retour en arrière.

Le guide complet, avec quoi faire si mtkclient n'atteint pas le robot : [docs/MACOS.md](docs/MACOS.md).
Un agent IA peut aussi le faire pour vous (voir plus haut). Testé de bout en bout sur un vrai
SKMC 1200 C3 depuis un Mac Apple silicon en novembre 2026 (Mac Intel et SKMC 1100 A1 pas encore
essayés : dites-nous). Merci à @julienperrault et @flexbox.

## Ce que fait Toque

- **Interface sombre, bleu nuit**, pensée pour l'écran 7" et le bouton rotatif du robot, fluide sur sa
  tablette d'origine (1 Go de RAM, Android 6) : pas de bibliothèque lourde, images
  sous-échantillonnées, vues recyclées. Dix couleurs d'accent, une roue des couleurs pour toute autre
  couleur lisible, et un thème clair dans *Réglages › Écran*.
- **Une appli, plusieurs robots** : chaque robot a sa fiche (port, protocole, programmes,
  températures) et ne montre que les programmes et les recettes qu'il sait faire. Sur le **Smart** :
  cuisson manuelle avec ses 21 températures (40 °C compris), Pétrir, Vapeur, Rissoler, Turbo,
  nettoyage, et les programmes du Smart (mixer, smoothie, œufs, sous-vide, mijoter, fermenter, riz,
  chauffer l'eau, prélaver, nettoyage long), avec les recettes Lidl qui lui sont destinées.
- **Recettes Lidl, directement depuis monsieur-cuisine.com**. Toque télécharge les recettes
  officielles du Monsieur Cuisine connect (environ 1 700 en français, avec les réglages machine de
  chaque étape) par l'API publique du site, chaque nuit ou sur demande : aucun compte, aucun
  serveur à gérer, plus besoin de l'appli d'origine. Requêtes espacées, et seules les recettes
  nouvelles ou modifiées sont retéléchargées. On peut toujours importer à la place celles de
  l'appli d'origine.
- **Recettes de la communauté sur [toqueapp.org](https://toqueapp.org)** : les recettes partagées
  par les utilisateurs de Toque arrivent sur le robot (*Réglages › Recettes › Communauté*), sans
  compte. Avec un compte Toque gratuit (facultatif, proposé une seule fois et plus jamais si on
  refuse ; à relier sur la même page), les favoris du site arrivent sur le robot et vos recettes
  peuvent être publiées depuis le robot. Le site est en français, anglais, allemand, espagnol,
  catalan et ukrainien ; le robot ne garde qu'une clé révocable, jamais le mot de passe.
- **Auteur, notes et avis** : chaque recette affiche qui l'a publiée et sa note sur le site, et la
  fiche charge les commentaires des utilisateurs (avec les réponses de Monsieur Cuisine).
- **Recherche qui marche** : insensible aux accents, aux pluriels et aux fautes de frappe
  (« bechamle », « risoto »), dans tous les alphabets (cyrillique, polonais…), et la liste montre
  toutes les recettes. Recherche par ingrédients (« poulet, curry »), filtres, favoris.
  Listes **Récentes** (dernières recettes cuisinées) et **Fréquentes** (les plus cuisinées), aussi
  accessibles d'un appui sous la barre de recherche de l'accueil.
- **Cuisine pas à pas** : chaque étape se lance d'un appui, l'étape suivante s'affiche toute
  seule, pesée intégrée, reprise après coupure. Les réglages qui se suivent sans geste à faire
  entre eux (rinçage, mixages en plusieurs vitesses…) partent ensemble d'un seul appui : la lame
  passe directement d'une vitesse et d'une température à l'autre, sans s'arrêter. Les étapes du
  site en **montée progressive de vitesse** (« ramp ») deviennent une étape machine par palier.
  Une recette en cours s'affiche sur l'accueil et peut y être **abandonnée** (✕), sauf pendant un
  programme.
- **Notes et quantités personnelles** : une note libre sur chaque recette, et un appui sur la
  quantité d'un ingrédient la change (1000 g d'eau → 800 g) ; les pesées et les mentions des étapes
  qui l'utilisent suivent en proportion (pavé numérique, fractions comprises). Favoris, notes et quantités corrigées suivent la recette
  quand une synchronisation la remplace par la même recette de l'autre source (site ou appli
  d'origine).
- **Pour plus ou moins de personnes** : − / + au-dessus des ingrédients, de la moitié au double de la
  recette. Tout ce qui se pèse ou se mesure suit (liste, pesées, « 500 g d'eau » dans les étapes),
  arrondi comme on l'écrirait à la main, avec le même arrondi partout ; œufs et cuillerées sont
  arrondis au demi. Temps, vitesses et températures restent ceux de la recette testée, avec un rappel
  de surveiller la cuisson, et une alerte quand le bol risque d'être trop plein (2,5 L de liquide au
  maximum).
- **Mes recettes** : écrivez les vôtres (titre, personnes, durée, une ligne par ingrédient, étapes
  avec leur réglage du robot et leur pesée, choisis avec les mêmes limites de sécurité que la cuisson
  manuelle), ou faites **votre version** d'une recette Lidl avec le crayon. Les synchronisations n'y
  touchent jamais. Réglages › Recettes › Mes recettes les exporte vers `Download/toque-mes-recettes.json`, ou
  les importe (pour les sauvegarder ou les passer à un autre robot).
- **Recettes des autres langues** (option dans Réglages › Recettes › Lidl) : les recettes absentes
  dans votre langue sont ajoutées en anglais, français, allemand, espagnol…, une seule fois
  chacune, avec leur langue sur la vignette, et leurs catégories prennent les noms de votre langue.
  La langue du catalogue Lidl peut aussi être choisie (par exemple les recettes espagnoles avec
  l'interface en catalan).
- **Sons** : une cloche sonne quand la quantité à peser est atteinte (son au choix dans les
  Réglages), et une mélodie forte retentit à la fin du dernier programme de la recette. Un réglage
  vérifié (« J'ai vérifié ce réglage ») est mémorisé pour les prochaines fois.
- **Cuisson manuelle**, programmes **Pétrir / Vapeur / Rissoler / Nettoyage**, **Turbo**, **balance**.
  Nettoyage : 1 L d'eau chaude et une goutte de liquide vaisselle, vitesse de 4 à 10, sans chauffe.
  La cuisson manuelle peut aussi **chauffer sans mélanger** : avec une température réglée (120 °C
  au plus), la vitesse descend jusqu'à 0.
- **Hors ligne** : tout fonctionne sans réseau (plus de « serveur injoignable » au démarrage).
- **Heure toujours juste** : le fuseau horaire est trouvé d'après la connexion Internet et l'heure
  vient du réseau (le robot n'a pas de pile d'horloge). Juste après l'allumage, l'horloge est réglée
  d'après le premier serveur qui répond : l'heure est juste en quelques secondes au lieu de rester
  sur 01:00. Réglable à la main dans Réglages › Langue et heure. L'heure ne s'affiche qu'une fois juste.
- **Mise en veille**, comme l'appli d'origine : après quelques minutes sans toucher l'écran ni le
  bouton rotatif (de 2 à 30 min, ou jamais, dans Réglages › Écran), un écran noir assombri affiche
  l'heure, et la température du bol quand il est chaud. Jamais pendant un programme ni sur la
  balance. Le geste qui réveille ne fait rien d'autre. Pendant la veille, l'anneau de la molette
  reste normal, passe en veilleuse blanche très faible ou s'éteint (Réglages › Molette et sons).
- **Capteur du bol inversé** : sur certains robots (vu sur un SKMC 1200 C3), la base signale le bol
  présent quand il est retiré ; Réglages › Sécurité › Inverser le capteur du bol corrige cela.
- **Pensé pour un robot sans boutons Android** : Toque s'ouvre au démarrage, et un bouton
  **« Retour à Toque »** reste affiché par-dessus l'appli d'origine quand on l'ouvre.
- **Mises à jour depuis GitHub** : Réglages › Mises à jour vérifie les
  [versions publiées](https://github.com/Wuki60/MrCuisineVibeConnect/releases) de ce dépôt et installe
  la nouvelle (sans rien demander sur un robot rooté, puis Toque redémarre). Refusé pendant un programme.
  Les certificats racines récents absents d'Android 6 (ISRG Root X1/X2 pour le serveur de
  téléchargement de GitHub, Sectigo, Google) sont embarqués : les téléchargements HTTPS marchent sur le robot.
  Quand une nouvelle version sort, Toque le signale au démarrage ou en sortant de veille (au plus
  toutes les quelques heures, jamais pendant un programme) ; « Plus tard » attend le lendemain.

## Sécurité : ce qui change par rapport à l'appli d'origine

Toutes les limites d'origine sont reprises (vitesse 3 maximum en chauffe et en sens inverse,
durées maximales, conditions du Turbo…). Plusieurs défauts relevés par l'analyse du protocole
sont corrigés :

| Défaut de l'appli d'origine | Toque |
|---|---|
| Le démarrage était demandé avant de vérifier le couvercle | Couvercle, erreurs et fraîcheur des mesures sont vérifiés **avant** tout démarrage |
| Aucune détection d'une base moteur muette | Plus de réponse pendant 1,5 s : tout s'arrête et une alerte s'affiche |
| Erreurs d'écriture série ignorées | Arrêt et alerte |
| Un STOP suivi d'un START pouvait ne jamais émettre d'arrêt | Tout redémarrage ou changement de sens attend un échantillon confirmant **moteur arrêté** |
| Lecture série sans resynchronisation | Trames fragmentées, collées ou corrompues gérées |
| La lame tournait parfois en vapeur | Vapeur à vitesse 0 |

Autres ajouts : confirmation (et montée progressive) avant de mixer un contenu à 60 °C ou plus,
limite de vitesse avec le fouet, alertes verrouillées jusqu'à acquittement, rien ne redémarre
tout seul après une pause ou une alerte. À froid, la lame va directement à la vitesse demandée. Le pilotage tourne dans **son propre processus** : un plantage de l'interface ne coupe
pas une cuisson, qui se termine normalement.

## Structure

```
core/   Logique pure Kotlin, testée sans Android
        protocol/  trames série : Connect (15 octets émis, 27 reçus, 4800 bauds),
                   Smart (38 octets émis, 53 reçus, 9600 bauds)
        robot/     une fiche par robot (port, protocole, programmes, températures, recettes Lidl)
        machine/   règles de sécurité, contrôleur, boucle 100 ms, liaisons (série root, TCP, démo)
        recipes/   modèle, import de la base d'origine, recherche
app/    Appli Android (minSdk 23), sans AndroidX
        machine/   service de pilotage (processus « :machine ») et IPC
        recipes/   catalogue local, synchro Lidl
        ui/        écrans
tools/  setup/toque-setup.ps1 (Windows) et setup/toque-setup.sh (macOS, installation complète
        pilotable par un agent IA), robot/ (root TWRP, écriture TWRP, restauration), scripts
        d'installation, de restauration (.ps1/.sh) et de publication, twin.py (jumeau numérique)
docs/   INSTALLATION.md, INSTALLATION.fr.md (Connect, Windows) ; MACOS.md, MACOS.en.md (Connect,
        macOS) ; SMART.md, SMART.en.md (Smart)
AGENTS.md  procédure d'installation pour un agent IA
```

## Compiler

Ouvrir le dossier dans **Android Studio**, attendre la synchronisation Gradle, puis
**Build › Build App Bundle(s) / APK(s) › Build APK(s)**. L'APK est dans
`app/build/outputs/apk/debug/`.

En ligne de commande : `gradlew assembleDebug` (et `gradlew :core:test` pour les tests).

## Tester sans le robot

1. **Mode Démo** (par défaut) : installer l'APK sur n'importe quel Android (ou l'émulateur
   d'Android Studio, en paysage). La base moteur est simulée ; Réglages › Avancé › Diagnostic
   permet d'ouvrir le couvercle ou de provoquer une erreur pour voir les sécurités.
   Réglages › Recettes › Lidl › Synchroniser télécharge les recettes sur n'importe quel appareil.
2. **Jumeau numérique** : le jumeau du [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk)
   reproduit le protocole réel indépendamment de Toque.
   `python tools/twin.py --sdk <dossier du SDK>` puis, dans Toque, Réglages › Avancé › Machine ›
   Jumeau (émulateur : hôte `10.0.2.2` ; appareil réel : `adb reverse tcp:8765 tcp:8765` et
   hôte `127.0.0.1`). `python tools/twin.py --send "lid 1"` ouvre le couvercle, etc.

## Ajouter une langue

L'appli est écrite en français ; chaque autre langue est un fichier dans
[`app/src/main/assets/lang/`](app/src/main/assets/lang) (`en.json` pour l'anglais).

1. Copier `en.json` en `<code>.json` (par exemple `de.json`), mettre `"_name"` (par exemple `"Deutsch"`)
   et traduire les valeurs. Les clés sont les textes français ; garder les `%s` / `%d`.
   Les textes manquants restent en français.
2. Recompiler l'appli, ou, sans recompiler, copier le fichier dans `Android/data/app.toque/files/lang/`
   sur l'appareil puis relancer Toque.

La langue apparaît alors dans Réglages › Langue et heure. `gradlew :core:test` vérifie chaque
fichier de langue.

## Publier une nouvelle version

1. Augmenter `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Committer et pousser sur `main`.

Le [workflow de publication](.github/workflows/release.yml) lance les tests, compile les deux APK et
publie la version `vX.Y.Z` avec, celui du Connect en premier (les anciennes versions de Toque prennent le
premier APK). Les robots la trouvent ensuite dans Réglages › Mises à jour.

Toutes les compilations sont signées avec la clé du projet (`app/signature/`, publique exprès) : un APK
compilé sur n'importe quel PC ou par GitHub s'installe par-dessus la version précédente. N'installez que
les APK publiés sur ce dépôt.

Sans GitHub Actions : `gradlew assembleRelease` (copier `app-release.apk` en `Toque-X.Y.Z.apk`), puis
`gradlew assembleRelease -PtoqueSmart=true` (le copier en `Toque-smart-X.Y.Z.apk`), puis
`powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1 -Apk Toque-X.Y.Z.apk -SmartApk Toque-smart-X.Y.Z.apk`
crée la version `vX.Y.Z` et y joint les deux APK (jeton GitHub : `GH_TOKEN`, `gh`, ou les identifiants
déjà enregistrés pour git).

## Installer sur le robot

- **Connect**, avec un agent IA : **[AGENTS.md](AGENTS.md)** (voir plus haut). À la main :
  **[docs/INSTALLATION.fr.md](docs/INSTALLATION.fr.md)** : root, installation, premier essai à vide,
  synchronisation des recettes, retour à l'appli d'origine.
- **Smart** : **[docs/SMART.md](docs/SMART.md)** : guide de root de la communauté, APK du Smart,
  essais à faire et comment faire un retour.

## Crédits

- Protocole : [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk) (MIT).
- Accès au robot : [Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack).
- Mode téléchargement MediaTek sous macOS : [mtkclient](https://github.com/bkerler/mtkclient) (GPLv3, téléchargé par `prepare`, non inclus ; `prepare` applique `tools/setup/mtkclient-macos-serial.patch`).
- Contributions : @pierok13 (veille, notes et quantités, paliers, sons, récentes, certificats Android 6,
  capteur du bol), @lucasbraud, @OUTSIDE2k8 (traduction espagnole), @Locodice67 (traduction allemande, heure au démarrage,
  script d'installation), @baptiste-gislot, @florentdestremau (bouton rotatif), @maximehuran (lumière
  du bouton en veille), @badrbelhaj1 (Magisk au redémarrage, TWRP du SKMC 1100 A1, chauffe sans
  mélange, menu des réglages, filtres et tri des recettes, minuteurs, roue des couleurs, couleurs de
  la molette, écran de démarrage, vérification rapide du port série, contrôles d'installation,
  corrections d'interface), @meuca1 (traduction catalane), @Flav974 (portage du Smart :
  protocole, programmes, captures), @Tidusneo24150 (Camybrith Cooking : plages de température et
  modes des recettes du Smart, relecture sur un vrai Smart), @pierrearnou (couleurs d'accent,
  interrupteurs), @oscarsanchezdm (langue des recettes, thème clair), @phanex (ukrainien, recherche
  dans tous les alphabets, stabilité de la balance, historique des pesées), @McSoda (essais sur robot
  et corrections, chien de garde de la boucle moteur), @julienperrault et @flexbox (scripts
  d'installation macOS, correctifs série mtkclient et flash depuis TWRP), et
  tous ceux qui ont testé leur robot et signalé des bugs.

Licence MIT. Ce dépôt ne contient aucune recette, image ni code de Lidl : chaque robot télécharge
lui-même les recettes sur monsieur-cuisine.com, pour l'usage personnel de son propriétaire.
