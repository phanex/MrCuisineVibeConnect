# Toque sur le Monsieur Cuisine Smart (SKMS 1200) : en test

🇬🇧 **[English version](SMART.en.md)**

> **Dans Toque depuis la version 0.8.0, encore en test.** Le mainteneur de Toque n'a pas de
> Monsieur Cuisine Smart : **ce sont les propriétaires de Smart qui testent et font un retour**
> dans l'[issue #59](https://github.com/Wuki60/MrCuisineVibeConnect/issues/59). Rien ici n'a été
> essayé par le projet lui-même sur un Smart : faites le premier essai bol vide, main sur
> l'interrupteur principal.

## D'où vient le code

- **Protocole et programmes du Smart** : portage de [Flav974](https://github.com/Flav974/toque-smart-ratatouille)
  (licence MIT). Chaque trame envoyée est une trame relevée sur l'appli d'origine ; ses captures sont
  rejouées octet par octet par les tests (`SmartProtocolTest`, `SmartControllerTest`).
- **Plages de température des programmes, noms des modes des recettes « MC3.0 », chien de garde
  de la base** : portage de [Tidusneo24150](https://github.com/Tidusneo24150/camybrith-cooking)
  (Camybrith Cooking, licence MIT).
- Et ceux qu'ils créditent : depau et le fil n° 38 d'EliasKotlyar (root), ihrapsa (TWRP), AroPix (MCS2MQTT).

## Ce qui change dans Toque, pour tous les robots

- **Une fiche par robot** : port, protocole, appli d'origine, programmes, températures, et le type
  de recettes du site Lidl (`MC2.0` pour le Connect, `MC3.0` pour le Smart).
- **Détection sans risque** : Toque regarde l'appli d'origine installée, la version d'Android et les
  ports présents, **sans rien envoyer à la base**. Au premier démarrage, la personne confirme son
  robot (Réglages › Robot pour changer). En « Machine réelle », la liaison avec la base reste fermée
  tant que le robot n'est pas confirmé.
- **Chaque robot ne voit que les recettes qu'il peut faire** : celles que le site lui destine, dont
  chaque réglage existe sur ce robot. Les autres sont comptées dans Réglages › Recettes Lidl
  (« N recettes masquées »). Les écrans (programmes, cuisson manuelle, éditeur) ne proposent que ce
  que le robot sait faire.
- Sur le Connect, rien ne change : même protocole, mêmes limites, et le tri des recettes reste léger.

## Ce que fait Toque sur le Smart

- Liaison `/dev/ttyMT1` à 9600 bauds. L'appli d'origine (`com.tecpal.device.mc30`) est mise de côté :
  son service `SerialPortMC30Service` est désactivé en « Machine réelle », réactivé par « Ouvrir
  l'appli d'origine ».
- **Cuisson manuelle**, avec les 21 températures du Smart (40 °C compris), vitesse 3 au plus en
  chauffe et en sens inverse.
- **Pétrir** (Malaxer de l'appli d'origine), **vapeur** (120 °C, vitesse 1), **rissoler** (Saisir :
  130 °C, brassage en sens inverse), **Turbo** (la base ne mesure la température qu'après une
  chauffe : Toque demande de confirmer que le contenu est froid), **nettoyage**.
- **Programmes du Smart** : mixer, smoothie, cuire des œufs, sous-vide (40 à 85 °C), mijoter (60 à
  100 °C), fermenter (30 à 50 °C), cuire du riz, chauffer l'eau, prélaver, nettoyage long. Sous-vide,
  mijoter et fermenter vont jusqu'à la durée de l'appli d'origine (12 h au plus).
- **Recettes Lidl du Smart** : Réglages › Recettes Lidl › Synchroniser (la première fois, toutes les
  fiches sont relues).
- **Pas encore** : chauffe sans mélange (vitesse 0) en cuisson manuelle ; import de la base de
  l'appli d'origine ; liste des codes d'erreur de la base.

## Installer

Prérequis : un Smart **rooté avec Magisk, ADB activé**. Toque n'a pas encore de guide de root pour le
Smart : suivez celui de [Camybrith Cooking](https://github.com/Tidusneo24150/camybrith-cooking/blob/main/docs/GUIDE.md).

1. Un autre portage (Camybrith Cooking, Édition Ratatouille) porte le même nom de paquet `app.toque`
   avec une autre signature : **désinstallez-le d'abord** (`adb uninstall app.toque`). Ses réglages et
   ses recettes perso sont alors effacés : exportez vos recettes avant si besoin.
2. Téléchargez **`Toque-smart-x.y.z.apk`** dans la
   [dernière version](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest) (celui **qui commence
   par `Toque-smart`** : l'autre APK est celui du Connect et ne marche pas sur la tablette du Smart), puis,
   depuis le dossier du dépôt :
   `powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1 -Apk Toque-smart-x.y.z.apk`
   (ou `adb install -r` puis `tools/robot/setup-root.sh` en root). Ensuite, Réglages › Mises à jour
   prend tout seul l'APK du Smart.

   Vous aviez une version de test `0.8.0-smart.N` ? Installez la 0.8.0 par-dessus avec la même
   commande, une seule fois : l'outil de mise à jour de ces versions de test ne la propose pas.
3. Au démarrage, « Quel est votre robot ? » : **Monsieur Cuisine Smart**.
4. Réglages › Machine › **Machine réelle** › Appliquer. **Premier essai bol vide**, main sur
   l'interrupteur principal.

## À tester, dans l'ordre

Bol vide d'abord, puis avec de l'eau. À chaque étape, ce qui compte : le robot fait-il ce qu'affiche
Toque, et s'arrête-t-il quand il le faut ?

1. **Liaison** : Réglages › Outils › Diagnostic de la base moteur : trames reçues, poids, couvercle, bol.
2. **Manuel** vitesse 2, 10 s ; recommencer en ouvrant le couvercle pendant la rotation (pause
   immédiate, reprise après fermeture).
3. **Chauffe** avec 500 ml d'eau : 37 °C vitesse 1 pendant 1 min, puis 40 °C.
4. **Turbo** : la question « Contenu froid ? », puis l'appui.
5. **Pétrir**, **Vapeur** (500 g d'eau au moins), **Rissoler**.
6. **Programmes du Smart** (avec de l'eau) : mixer, smoothie, œufs, sous-vide 60 °C 15 min, mijoter,
   fermenter, riz, chauffer l'eau, prélaver, nettoyage long.
7. **Recettes** : synchroniser ; les recettes propres au Smart (sous-vide, mijoter…) sont là ; en
   faire une en entier.
8. **Arrêt sans Toque** (important, car sous-vide, mijoter et fermenter peuvent durer des heures) :
   avec de l'eau, lancer « Chauffer l'eau » ou un manuel 60 °C vitesse 1, puis forcer l'arrêt de
   Toque (`adb shell am force-stop app.toque`). Le moteur **et la chauffe** doivent s'arrêter en
   quelques secondes. Dites-nous si la chauffe continue.
9. « Ouvrir l'appli d'origine », puis « Retour à Toque ».

## Faire un retour

Dans l'issue d'appel aux testeurs du Smart (ou une nouvelle issue) : la référence sous le robot
(SKMS 1200 A1…), la version d'Android et le firmware de la base (écran Diagnostic), ce qui marche et
ce qui ne marche pas, et le journal (Réglages › Outils › Journal).

## Revenir à l'appli d'origine

`tools\restore-robot.ps1` réactive l'appli d'origine (son service du port série compris) ;
`tools\restore-robot.ps1 -Uninstall` désinstalle aussi Toque.
