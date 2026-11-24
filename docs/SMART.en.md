# Toque on the Monsieur Cuisine Smart (SKMS 1200): in test

🇫🇷 **[Version française](SMART.md)**

> **In Toque since version 0.8.0, still in test.** Toque's maintainer does not own a Monsieur Cuisine
> Smart: **Smart owners test it and report back** in
> [issue #59](https://github.com/Wuki60/MrCuisineVibeConnect/issues/59). Nothing here has been tried by
> the project itself on a Smart: do the first run with an empty bowl, hand on the main switch.

## Where the code comes from

- **Smart protocol and programs**: [Flav974](https://github.com/Flav974/toque-smart-ratatouille)'s
  port (MIT licence). Every frame sent is one captured from the stock app; his captures are replayed
  byte for byte by the tests (`SmartProtocolTest`, `SmartControllerTest`).
- **Program temperature ranges, recipe mode names of "MC3.0" recipes, the base's watchdog**:
  [Tidusneo24150](https://github.com/Tidusneo24150/camybrith-cooking)'s port (Camybrith Cooking,
  MIT licence).
- And those they credit: depau and EliasKotlyar's thread #38 (root), ihrapsa (TWRP), AroPix (MCS2MQTT).

## What changes in Toque, for every robot

- **One profile per robot**: port, protocol, stock app, programs, temperatures, and the kind of Lidl
  recipes (`MC2.0` for the Connect, `MC3.0` for the Smart).
- **Safe detection**: Toque looks at the installed stock app, the Android version and the ports
  present, **without sending anything to the base**. On first start the person confirms the robot
  (Settings › Robot to change it). In "Real machine", the link to the base stays closed until the
  robot is confirmed.
- **Each robot only sees the recipes it can make**: the ones the site makes for it, whose every
  setting exists on that robot. The others are counted in Settings › Lidl recipes ("N recipes
  hidden"). Screens (programs, manual cooking, editor) only offer what the robot can do.
- On the Connect nothing changes: same protocol, same limits, and sorting the recipes stays light.

## What Toque does on the Smart

- Link on `/dev/ttyMT1` at 9600 baud. The stock app (`com.tecpal.device.mc30`) is set aside: its
  `SerialPortMC30Service` is disabled in "Real machine" and enabled again by "Open the stock app".
- **Manual cooking** with the Smart's 21 temperatures (40 °C included), speed 3 at most when heating
  or in reverse.
- **Knead** (the stock app's kneading), **steam** (120 °C, speed 1), **sauté** (130 °C, reverse
  stirring), **Turbo** (the base only measures the temperature after heating: Toque asks to confirm
  that the content is cold), **cleaning**.
- **Smart programs**: blend, smoothie, boil eggs, sous-vide (40 to 85 °C), slow cooking (60 to
  100 °C), ferment (30 to 50 °C), cook rice, heat water, pre-rinse, long cleaning. Sous-vide, slow
  cooking and ferment can run as long as in the stock app (12 h at most).
- **The Smart's Lidl recipes**: Settings › Lidl recipes › Sync (the first time, every recipe is read
  again).
- **Not yet**: heating without stirring (speed 0) in manual cooking; importing the stock app's
  database; the list of the base's error codes.

## Install

You need a Smart **rooted with Magisk, with ADB enabled**. Toque has no root guide for the Smart yet:
follow [Camybrith Cooking's](https://github.com/Tidusneo24150/camybrith-cooking/blob/main/docs/GUIDE.en.md).

1. Another port (Camybrith Cooking, Ratatouille Edition) has the same package name `app.toque` with
   a different signature: **uninstall it first** (`adb uninstall app.toque`). Its settings and own
   recipes are erased: export your recipes first if needed.
2. Download **`Toque-smart-x.y.z.apk`** from the
   [latest release](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest) (the one **starting with
   `Toque-smart`**: the other APK is the Connect's and does not work on the Smart's tablet), then, from the
   repository folder:
   `powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1 -Apk Toque-smart-x.y.z.apk`
   (or `adb install -r`, then `tools/robot/setup-root.sh` as root). After that, Settings › Updates
   takes the Smart APK by itself.

   Had a `0.8.0-smart.N` test version? Install 0.8.0 over it with the same command, once: the updater
   of those test versions does not offer it.
3. On start, "Which robot is this?": **Monsieur Cuisine Smart**.
4. Settings › Machine › **Real machine** › Apply. **First run with an empty bowl**, hand on the main
   switch.

## What to test, in this order

Empty bowl first, then with water. At each step what matters is: does the robot do what Toque shows,
and does it stop when it should?

1. **Link**: Settings › Tools › Motor base diagnostics: frames received, weight, lid, bowl.
2. **Manual** speed 2, 10 s; again, opening the lid while it turns (immediate pause, resumes after
   closing).
3. **Heating** with 500 ml of water: 37 °C speed 1 for 1 min, then 40 °C.
4. **Turbo**: the "Cold content?" question, then the press.
5. **Knead**, **Steam** (at least 500 g of water), **Sauté**.
6. **Smart programs** (with water): blend, smoothie, eggs, sous-vide 60 °C 15 min, slow cooking,
   ferment, rice, heat water, pre-rinse, long cleaning.
7. **Recipes**: sync; the Smart's own recipes (sous-vide, slow cooking…) are there; cook one fully.
8. **Stop without Toque** (important, since sous-vide, slow cooking and ferment can last hours): with
   water, start "Heat water" or a manual 60 °C speed 1, then force-stop Toque
   (`adb shell am force-stop app.toque`). The motor **and the heating** must stop within seconds.
   Tell us if the heating goes on.
9. "Open the stock app", then "Back to Toque".

## Reporting

In the Smart testers issue (or a new issue): the model under the robot (SKMS 1200 A1…), the Android
version and the base firmware (Diagnostics screen), what works and what doesn't, and the log
(Settings › Tools › Log).

## Back to the stock app

`tools\restore-robot.ps1` gives the motor back to the stock app (its serial-port service included);
`tools\restore-robot.ps1 -Uninstall` also uninstalls Toque.
