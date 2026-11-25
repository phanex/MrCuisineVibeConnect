# Installer Toque sur le Monsieur Cuisine Connect depuis un Mac

🇬🇧 **[English version](MACOS.en.md)**

> **Avec un agent IA** (Claude, ChatGPT…) qui pilote votre Mac : donnez-lui le lien du dépôt et
> demandez-lui de suivre [AGENTS.md](../AGENTS.md). Le script
> [`tools/setup/toque-setup.sh`](../tools/setup/toque-setup.sh) automatise toutes les étapes
> ci-dessous (`bash tools/setup/toque-setup.sh status` indique où vous en êtes).

Sous macOS, SP Flash Tool et son pilote Windows sont remplacés par
[mtkclient](https://github.com/bkerler/mtkclient) pour les deux opérations en mode téléchargement
MediaTek (`backup` et `flash-twrp`), et par [`tools/robot/twrp-flash.sh`](../tools/robot/twrp-flash.sh)
pour écrire `boot`/`recovery` depuis TWRP (`flash-root` et `unroot`). Réalisé de bout en bout sur un
vrai SKMC 1200 C3 depuis un Mac Apple silicon en novembre 2026, grâce aux correctifs série de
[`tools/setup/mtkclient-macos-serial.patch`](../tools/setup/mtkclient-macos-serial.patch) appliqués
par `prepare`.

Pour les sections communes après installation (premier essai bol vide, démarrage, recettes Lidl),
voir [docs/INSTALLATION.fr.md](INSTALLATION.fr.md#4-premier-essai--bol-vide).

## Ce qu'il faut

- Un Mac (Apple silicon ou Intel) avec [Homebrew](https://brew.sh) : `brew install python libusb`
  (Python 3.10+), et git (`xcode-select --install`). Aucun pilote à installer.
- Un câble de données **USB-A mâle / USB-A mâle** et un petit tournevis Torx. Un câble USB-A ↔ USB-C
  ne marche **pas** : sur un Mac qui n'a que des ports USB-C, utiliser un **adaptateur USB-C vers
  USB-A** (USB-A femelle) ou un hub USB entre le Mac et le câble A↔A.

> **Règles d'allumage.** Allumer le robot câble **débranché** pour les étapes adb (`root`,
> `flash-root`, `android`, `install`, `wifi`), puis brancher le câble une fois l'écran affiché
> (allumé avec le câble, il reste bloqué sur un écran de batterie). Seule exception : les étapes en
> mode téléchargement (`backup`, `flash-twrp`), où le robot démarre **éteint** avec le câble
> **branché**, puis est allumé quand le script le demande.
>
> Sur Apple silicon, cliquer sur **Autoriser** si macOS affiche « Autoriser l'accessoire à se
> connecter ? » ; en mode téléchargement, éteindre puis rallumer ensuite le robot.

## 1. Préparation, sauvegarde et TWRP

1. `bash tools/setup/toque-setup.sh prepare` télécharge adb, Magisk v31.0, TWRP, le fichier scatter,
   le dernier APK Connect (`Toque-X.Y.Z.apk`) et mtkclient v2.1.4.1 épinglé (avec ses correctifs
   série macOS et ses paquets Python vérifiés par SHA-256) dans `~/Toque`.
2. Robot éteint (interrupteur arrière), trappe dévissée, câble **uniquement sur le port de droite
   marqué « Android USB »** (l'autre peut détruire la carte).
3. `bash tools/setup/toque-setup.sh backup`, puis allumer le robot quand mtkclient attend l'appareil.
   *Handshake failed, retrying...* toutes les 10 s pendant l'attente est normal. Le script crée et
   vérifie `~/Toque/backup/boot-original.img` et `recovery-original.img` (16 Mo chacun) : **copier
   `~/Toque/backup/` sur une clé USB ou dans le cloud, c'est votre seule voie de retour.** Après
   chaque session mtkclient, le robot reste en mode téléchargement (écran noir) : l'éteindre avant
   l'étape suivante.
   - **SKMC 1100 A1 et SKMC 1200 D4** (carte `spm188a`) : lancer `bash tools/setup/toque-setup.sh own-twrp`
     avant `flash-twrp` pour reconstruire `~/Toque/twrp-own-kernel.img` avec le noyau du robot.
     `flash-twrp` lit la carte dans la sauvegarde et s'arrête, sans rien écrire, s'il manque.
4. `bash tools/setup/toque-setup.sh flash-twrp` : écrit TWRP dans `boot` uniquement et le relit.
5. Éteindre le robot, débrancher le câble, rallumer → **TWRP** démarre. Rebrancher le câble.

### Si mtkclient n'atteint pas le robot

- Vérifier le câble A↔A, le port de droite et l'adaptateur USB-C → USB-A ; juste après l'allumage,
  `system_profiler SPUSBDataType` doit lister **MT65xx Preloader** (vendor ID `0x0e8d`).
- En cas de *Sync error* ou *Failed to upload da* : lancer `prepare` (`status` doit afficher
  *mtkclient: v2.1.4.1 + macOS serial fixes*), éteindre le robot et recommencer (`--libusb` essaie
  libusb à la place).
- Repli : faire uniquement `backup` et `flash-twrp` sur un PC Windows
  ([INSTALLATION.fr.md](INSTALLATION.fr.md)), copier les deux sauvegardes dans `~/Toque/backup/`,
  lancer `backup` sur le Mac pour les vérifier, puis continuer ci-dessous (toute la suite utilise
  uniquement adb).

## 2. Root, Android et Toque

1. Dans TWRP : `bash tools/setup/toque-setup.sh root` (patche le `boot` d'origine avec Magisk,
   active ADB, autorise `~/.android/adbkey.pub`).
2. Toujours dans TWRP : `bash tools/setup/toque-setup.sh flash-root` (écrit TWRP dans `recovery` et
   `boot-magisk.img` dans `boot` via `twrp-flash.sh`, avec vérification avant et après ; ne pas
   éteindre le robot pendant l'opération).
3. Éteindre, débrancher, rallumer → l'appli d'origine démarre. Brancher le câble.
4. `bash tools/setup/toque-setup.sh android` (installe l'appli Magisk ; appuyer sur **Autoriser**
   sur le robot ; vérifie la partition `boot`).
5. `bash tools/setup/toque-setup.sh install` (ou `bash tools/install-robot.sh [--apk FICHIER] [--ip IP]`),
   puis sur le robot (**bol vide**, couvercle fermé, main près de l'interrupteur) :
   **Réglages › Avancé › Machine › Machine réelle › Appliquer**, puis relancer `install` pour vérifier le mode
   réel.
6. Facultatif : `bash tools/setup/toque-setup.sh wifi` pour activer ADB par Wi-Fi (port 5555, réseau
   domestique uniquement), retirer le câble et refermer la trappe.

Continuer avec [Premier essai — bol VIDE](INSTALLATION.fr.md#4-premier-essai--bol-vide).

## Revenir à l'appli d'origine / retirer le root

- Garder le root, revenir à l'appli d'origine : Réglages › Avancé › « Revenir à l'appli d'origine »,
  ou `bash tools/restore-robot.sh --uninstall`.
- Retirer le root et TWRP (faire la ligne ci-dessus d'abord) : `bash tools/setup/toque-setup.sh unroot`
  (depuis TWRP ; ajouter `--download-mode` si le robot ne démarre plus).
