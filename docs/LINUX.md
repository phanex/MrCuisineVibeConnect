# Installer Toque depuis Linux (Connect)

🇬🇧 **[English version](LINUX.en.md)**

Même procédure et mêmes vérifications que sous macOS ([MACOS.md](MACOS.md)), avec le même script,
`tools/setup/toque-setup.sh` : [mtkclient](https://github.com/bkerler/mtkclient) fait les deux étapes
qui demandent le mode téléchargement MediaTek (`backup`, `flash-twrp`), TWRP écrit le reste, sans
pilote ni SP Flash Tool.

**Essayé** de bout en bout le 27 novembre 2026 depuis **Fedora 44** (x86_64, Python 3.14) sur un
vrai robot étiqueté SKMC 1200 dont la carte est `spm188a` (carte du SKMC 1100 A1, voir #111 / #115) :
`prepare`, `backup`, `own-twrp`, `flash-twrp` (TWRP démarre, le tactile marche), `root`,
`flash-root`, `android`, `install`, puis le premier essai (cuisson manuelle, bol vide) et la synchro
des recettes Lidl. mtkclient est passé par **libusb** (le défaut sous Linux) et a atteint le
preloader du premier coup ; les correctifs série macOS n'ont pas servi. Autres distributions : le
script affiche la commande équivalente, non essayé.

## Une seule fois (sudo)

```sh
# Fedora (Debian/Ubuntu/Arch : 'toque-setup.sh prepare' affiche l'équivalent)
sudo dnf install -y libusb1 python3 git unzip curl android-tools
sudo cp tools/setup/linux/51-toque-mtk.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules && sudo udevadm trigger
```

La règle donne à votre utilisateur l'accès au périphérique USB MediaTek (fabricant `0e8d`) et dit à
ModemManager (qui a ses propres règles MediaTek et prendrait sinon le port) de l'ignorer. Sans elle :
`sudo systemctl stop ModemManager` le temps des étapes en mode téléchargement.

## Étapes

Depuis le dossier du dépôt, chaque étape quand la précédente le dit (`status` dit où vous en êtes) :

```sh
bash tools/setup/toque-setup.sh prepare      # téléchargements, SHA-256, mtkclient dans ~/Toque
bash tools/setup/toque-setup.sh backup       # robot ÉTEINT, câble branché, lancer, puis ALLUMER le robot
bash tools/setup/toque-setup.sh own-twrp     # cartes SKMC 1100 A1 / spm188a (flash-twrp le dit)
bash tools/setup/toque-setup.sh flash-twrp   # même séquence d'allumage que backup
bash tools/setup/toque-setup.sh root         # TWRP à l'écran, câble branché après son démarrage
bash tools/setup/toque-setup.sh flash-root
bash tools/setup/toque-setup.sh android      # appuyer sur Autoriser sur le robot dans les 10 s
bash tools/setup/toque-setup.sh install
```

`flash-twrp` lit la carte dans la sauvegarde de boot et refuse d'écrire le TWRP du guide sur une
carte `spm188a` tant que `own-twrp` n'a pas été lancé. **Gardez une copie de `~/Toque/backup/`** :
c'est le chemin du retour.

Câble : USB-A mâle ↔ USB-A mâle dans le port **« Android USB »** (à droite). PC avec seulement de
l'USB-C : un adaptateur USB-C → USB-A côté PC, jamais un câble USB-C ↔ USB-A.

## Si mtkclient n'atteint pas le robot

1. `lsusb | grep 0e8d` juste après l'allumage doit montrer `0e8d:2000` (preloader) quelques
   secondes. Rien : câble, port ou hub. (`0e8d:2008`, c'est le robot sous Android en MTP : l'éteindre
   d'abord.)
2. `/dev/ttyACM*` apparaît mais mtkclient reste muet : réessayer avec `--serial` (mtkclient par le
   port série, avec les correctifs de `mtkclient-macos-serial.patch`).
3. `journalctl -f` en branchant : si ModemManager sonde le port, la règle udev manque.
4. Après une session, le robot reste en mode téléchargement (écran noir) : l'éteindre avant l'étape
   suivante en mode téléchargement.
