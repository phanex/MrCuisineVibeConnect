# AGENTS.md: install Toque on a Monsieur Cuisine (Connect or Smart)

This file is for an AI agent (Claude, ChatGPT, Copilot…) that a person has pointed at this
repository with a request such as *"Install Toque on my Monsieur Cuisine"*. It is the complete
procedure, written so that the agent does everything it can by itself and asks the person only for
what needs hands, eyes or a Windows security prompt.

Humans: the same procedure by hand is in [docs/INSTALLATION.md](docs/INSTALLATION.md) (Connect) and
[docs/SMART.en.md](docs/SMART.en.md) (Smart).

## First: which robot?

Toque runs on two Lidl robots that look alike but have different tablets, protocols and install
procedures. **Before anything else, ask the person to read the model on the label under the robot**
(it starts with `SKMC` or `SKMS`), and follow the matching line:

| Label under the robot | Robot | Tablet | Follow | Toque APK |
|---|---|---|---|---|
| **SKMC 1200** (A1, B2, C3) | Monsieur Cuisine **Connect** | Android 6.0 | This guide, from *What you are doing* | `Toque-X.Y.Z.apk` |
| **SKMC 1100 A1** | Monsieur Cuisine **Connect** | Android 6.0 | This guide, with the extra step `own-twrp` | `Toque-X.Y.Z.apk` |
| **SKMS 1200** | Monsieur Cuisine **Smart** (in test) | Android 8.1 | *Monsieur Cuisine Smart (SKMS 1200)* below | `Toque-smart-X.Y.Z.apk` |
| anything else | unknown | | Stop. Ask the person to open an issue with a photo of the label | |

- If ADB already works (a robot rooted earlier), check that the tablet agrees with the label:
  `adb shell getprop ro.build.version.release` gives `6.0` on a Connect and `8.1.0` on a Smart, and
  `adb shell pm list packages` lists the stock app, `de.silpion.mc2` (Connect) or
  `com.tecpal.device.mc30` (Smart). If the label and the tablet disagree, stop and ask.
- **Never run `tools\setup\toque-setup.ps1` on a Smart.** Its TWRP, scatter file and partition
  addresses are the Connect's: written to a Smart, they can leave its tablet unusable.
- **Each robot has its own APK; never swap them.** The Smart's APK runs in the virtual machine's
  safe mode, which its Android 8.1 needs (its JIT compiler miscompiles some code) and which would make
  a Connect much slower. Once installed, *Settings › Updates* takes the right one by itself.
- On first start, Toque asks *Which robot is this?* (the detected one is listed first). The person
  confirms the robot on the label; until then Toque sends nothing to the motor base.

## What you are doing (Connect)

The Lidl **Monsieur Cuisine Connect** (model **SKMC 1200**) is a cooking robot whose screen is an
Android 6 tablet (MediaTek MT6580). Toque replaces its stock app. To get there you will:

1. back up two partitions of the tablet (boot, recovery) — the only way back;
2. boot TWRP (a recovery system) and patch the original boot with **Magisk** (root);
3. flash the patched boot, keep TWRP as recovery;
4. install the Toque APK, enable ADB over Wi-Fi.

Flashing happens over USB with **SP Flash Tool** while the robot is switched off (MediaTek BROM
mode). Everything else goes through **adb**. It was done end to end on a real robot in October 2026
with exactly these files; total time 1–2 hours, most of it waiting for the person.

## Rules for the agent

- **Talk to the person in their language**, in short messages. Before starting, tell them what
  they will need (list below), that it voids the warranty, that a mistake can leave the robot's
  tablet unusable, and get a clear *yes*.
- **Never skip the backup step**, never flash anything other than `boot` and `recovery`, never
  use *Format* or *Firmware Upgrade* in SP Flash Tool, only *Download Only*.
- **Never type passwords, never change Windows security settings yourself.** The person turns
  memory integrity off/on and answers User Account Control prompts.
- Download only from the URLs below, and let the script check the SHA-256 of every file. If a
  checksum does not match, stop.
- Ask before each step that writes to the robot (`flash-twrp`, `root`, `flash-root`) — say what it
  does in one sentence and wait for *yes*.
- If the person is using the PC (a game, a call…), ask before taking over the screen.
- When a step prints `STOP:`, do not improvise a workaround on the robot: read the cause, fix it
  on the PC side or ask the person, then run the step again.
- Know the robot before you start (*First: which robot?*). **SKMC 1200**, revisions A1, B2 and C3,
  have been installed with this procedure. An **SKMC 1100 A1** needs TWRP rebuilt around its own,
  newer kernel (issue #10): one more step, `own-twrp`, right after `backup` (see *SKMC 1100 A1*
  below); everything else is the same. A Monsieur Cuisine **Smart** (SKMS 1200) is different
  hardware: none of the Connect steps apply, see *Monsieur Cuisine Smart (SKMS 1200)*.
- The SP Flash Tool archive has a public password printed on its download page; typing it to
  extract the archive is fine. Any other password: never.

## What the person needs

- A Windows 10/11 PC with a **USB-A** port, Internet, and the agent able to run PowerShell.
- A **USB-A male ↔ USB-A male data cable**. USB-A ↔ USB-C does **not** work: the robot's port is a
  device port and the PC's USB-C port never sees it.
- A small Torx screwdriver (maintenance cover under the robot).
- The robot within reach of the PC, connected to the mains, and the home Wi-Fi password (for the
  end).

## The tool

Everything is driven by one script, from the repository folder:

```
powershell -ExecutionPolicy Bypass -File tools\setup\toque-setup.ps1 -Step <step>
```

Work folder: `C:\Toque` (change with `-Work`). Every step can be re-run; `-Step status` says what
is done and what comes next. Output lines:

- `OK:` done and verified;
- `HUMAN:` relay this to the person, word for word in their language, and wait until they say it
  is done;
- `STOP:` the step failed safely (exit code 2) — nothing irreversible was done after the failure;
- exit code 3: the step is waiting for a human action; run it again afterwards.

A log is kept in `C:\Toque\setup-log.txt`, state in `C:\Toque\state.json`.

## Procedure (Connect)

| # | Step | Who | What happens |
|---|------|-----|--------------|
| 0 | clone | agent | Get this repository on the PC (git clone or the ZIP of `main`). |
| 1 | `prepare` | agent | Downloads adb, Magisk v31.0, TWRP and the scatter file, the latest Toque APK for the Connect (`Toque-X.Y.Z.apk`, never `Toque-smart-*`); checks SHA-256. |
| 1b | (browser) | agent + person | Two files need a browser, see *Manual downloads*. Then `prepare` again. |
| 2 | `driver` | agent + person | Installs the MediaTek PreLoader USB VCOM driver (UAC prompt). May ask to turn memory integrity off and restart. |
| 3 | `backup` | person, then agent | Cover off, cable in the **right-hand "Android USB" port**, robot off; the script reads `boot` and `recovery`; the person switches the robot on when told. Verifies two 16 MiB Android images. |
| 3b | `own-twrp` | agent | **SKMC 1100 A1 only.** Builds `C:\Toque\twrp-own-kernel.img` on the PC: the TWRP of this guide with the robot's own kernel, taken from the `boot` backup. Nothing is sent to the robot; `flash-twrp` and `flash-root` then write that image. |
| 4 | `flash-twrp` | agent + person | Writes TWRP into `boot` (Download Only). The person power-cycles: off, **unplug**, on, plug back → TWRP. |
| 5 | `root` | agent | In TWRP: patches the *original* boot with Magisk, enables ADB in Android, authorizes the PC's ADB key, pulls `boot-magisk.img`. |
| 6 | `flash-root` | agent + person | Writes `boot-magisk.img` into `boot` and TWRP into `recovery`. Power cycle as above → stock app. |
| 7 | `android` | agent + person | Checks Magisk, installs its app; the person taps **Grant** on the robot once. Checks the boot partition. |
| 8 | `install` | agent | Installs Toque, allows it in Magisk, starts it. |
| 9 | first run | person | *Which robot is this?*: **Monsieur Cuisine Connect**. Empty bowl: Settings › Machine › Real machine › Apply. Success: badge **not** in demo mode **and** journal line showing the real link, e.g. `liaison ouverte : Base moteur (/dev/ttyMT…)`; then manual cooking 10 s speed 2. |
| 10 | `wifi` | agent | ADB over Wi-Fi (port 5555), so the cable can go and the cover go back on. |
| 11 | `cleanup` | agent + person | Removes the MediaTek driver; the person can turn memory integrity back on. |

Then: Toque › Settings › Lidl recipes › **Sync** (about 15 minutes the first time). The recipe count
stays at 0 and "never synced" stays shown until every recipe has been fetched: that is normal, do not
restart the sync.

### SKMC 1100 A1: TWRP with the robot's own kernel

The TWRP image of this guide carries a kernel built in April 2018. An SKMC 1100 A1 (system image
`v4.3a_2022-09-15`, stock app 1.1.28) runs a kernel built in September 2022, so its TWRP is rebuilt
around that kernel:

- Run `-Step own-twrp` once, after `backup` and before `flash-twrp`. It prints both kernel
  versions, takes the kernel and device tree from `C:\Toque\backup\boot-original.img` and
  everything else (TWRP itself, the header) from `C:\Toque\hack\recovery.img`, writes
  `C:\Toque\twrp-own-kernel.img`, reads it back and records its SHA-256 in `state.json`. It
  downloads nothing, sends nothing to the robot, and refuses to build if the two images do not
  share the same layout.
- `flash-twrp` and `flash-root` then write that image instead of `hack\recovery.img`;
  `-Step status` says which one is selected. To go back to the guide's image, delete the
  `twrp-own-kernel-sha256` line from `C:\Toque\state.json`.

Done end to end on one SKMC 1100 A1 in November 2026: TWRP started with a stable picture and
working touch, then `root`, `flash-root` (10.98 MB and 26.98 MB sent in console mode), `android`,
`install` and the first run went as on an SKMC 1200. The rest of this guide applies unchanged,
including TWRP restarting after 6 min 40 s and the reversed bowl sensor. On that robot the tablet
restarted by itself after each flash, straight into TWRP and then into Android with the cable
still plugged in: when `adb devices` already shows the robot, the power cycle is not needed.

After the install, `adb reboot recovery` starts the same TWRP from the `recovery` partition (done
twice on that robot: adb in `recovery` after 15 s, working touch) and `adb reboot` returns to
Android. Started that way TWRP is not restarted after 6 min 40 s: the kernel logs
`init_wdt:RECOVERY_BOOT` instead of `init_wdt:NORMAL_BOOT` and does not count, and TWRP was still
running after 7 minutes.

### Power-on rules (the source of most confusion)

- **SP Flash Tool steps** (`backup`, `flash-twrp`, `flash-root`): robot **off**, cable **plugged
  in**, start the step, then switch the robot **on**. The tool catches it before Android starts.
- **adb steps** (`root`, `android`, `install`, `wifi`): switch the robot on with the cable
  **unplugged**, plug it in once the screen is up. Switched on with the cable in, the robot stops on
  a **battery / charging screen**: switch off, unplug, switch on, plug.
- Only the right-hand port, labelled **"Android USB"**, ever. The other port can destroy the board.

### Manual downloads (need a browser)

1. **MediaTek PreLoader USB VCOM driver** — Microsoft Update Catalog:
   `https://www.catalog.update.microsoft.com/Search.aspx?q=MediaTek%20PreLoader%20USB%20VCOM`.
   Pick the update whose download is named
   `20896845_fdc6bb5aa9a9bac99adf85d931d6c21d1130a96e.cab` (the hex part is its SHA-1; SHA-256
   `ce72e97c07582ad41debfaeee6f6284ecd28285df5f9caa05193653fb236561f`). Save it in
   `C:\Toque\downloads\`.
2. **SP Flash Tool v5.1924 for Windows** — `https://spflashtool.com/download`, file
   `SP_Flash_Tool_v5.1924_Win.rar`. Upstream ships only this password-protected RAR, not a ZIP: the
   AES password is `spflashtool.com` (published on that page), and Windows 10 and Windows 11 before
   23H2 need 7-Zip or WinRAR to open it. A *scripted* direct download needs the header
   `Referer: https://spflashtool.com/download`, otherwise the server returns HTML instead of the
   file (a person can just use the download page). Extract it into `C:\Toque\SP_Flash_Tool\`, and
   the script checks `flash_tool.exe` (SHA-256
   `67e9eb48161087d43eca845b2dc0e9beacac3c0381bd1ce7989d48b17e2084a7`) and
   `MTK_AllInOne_DA.bin` (`9cbf39f3925fe88d18bd4c4e45dd1c01741bf7a31a4f69252dfb318d229c0346`);
   these hashes apply to the files inside the RAR.

Ask the person before downloading (state the file names, sources and sizes: ~130 KB and ~40 MB).

### Files the script downloads by itself

| File | Source | SHA-256 |
|---|---|---|
| Android platform tools (adb) | `https://dl.google.com/android/repository/platform-tools-latest-windows.zip` | (latest, from Google) |
| Magisk v31.0 | `https://github.com/topjohnwu/Magisk/releases/download/v31.0/Magisk-v31.0.apk` | `2c8a488b9a5293e578e95ae4f07e3c57aba4feec4a52ca4dd852a2692d6dd4e8` |
| TWRP `hacks/recovery.img` | [EliasKotlyar/Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack) | `fc9ac999fc3b0a6c0cb563f39359084a6fcc211aeb42e7b754990afa4b543d3e` |
| Scatter `l706_dfbh_v_695scatter.txt` | same repository | `13b8260dba9e4efba874c09e35803af43cc69556ba0f1937226240b0ab318364` |
| Toque `Toque-X.Y.Z.apk` (Connect) | [latest release](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest) of this repository | (signed with the project key) |

### SP Flash Tool: console mode, and the GUI fallback

The script runs SP Flash Tool in console mode (`flash_tool.exe -i <config>.xml`, configs written to
`C:\Toque\spft-*.xml`). The reference run in October 2026 used the **SP Flash Tool window** for
these three operations; the console configuration was then used successfully for all three on a
second robot in November 2026 (readback 2 × 16 MiB, `flash-twrp` 10.92 MB, `flash-root` 26.92 MB
sent). If a console step fails (`STOP:`), do the same
operation in the window — with a computer-use tool, or by guiding the person:

**GUI fallback: readback** (step `backup`)
1. Start `C:\Toque\SP_Flash_Tool\flash_tool.exe`. Tab **Download** › *Scatter-loading File* ›
   choose `C:\Toque\hack\l706_dfbh_v_695scatter.txt`.
2. Tab **Readback** › **Add** twice. Double-click each row and set: *Region* `EMMC_USER`,
   *Start Address* `0x1d20000`, *Length* `0x1000000`, file `C:\Toque\backup\boot-original.img`; then
   `0x2d20000`, `0x1000000`, `C:\Toque\backup\recovery-original.img`.
3. Robot off, cable in. Click **Read Back**, then switch the robot on. Wait for the green tick.
4. Close SP Flash Tool and run `-Step backup` again: it verifies the two files.

**GUI fallback: Download Only** (steps `flash-twrp`, `flash-root`)
1. Tab **Download**, scatter file loaded as above, top drop-down **Download Only**.
2. Untick every row, then tick only:
   - `flash-twrp`: **boot** = `C:\Toque\hack\recovery.img`;
   - `flash-root`: **boot** = `C:\Toque\boot-magisk.img` and **recovery** = `C:\Toque\hack\recovery.img`.
   After `own-twrp` (SKMC 1100 A1), use `C:\Toque\twrp-own-kernel.img` in place of
   `hack\recovery.img` in both. Double-click the *Location* cell to choose the file. Check that no
   other row is ticked.
3. Robot off, cable in. Click **Download**, then switch the robot on. Wait for the green tick.
4. Continue with the next step (`root` after `flash-twrp`, `android` after `flash-root`). Those
   steps check the robot itself, so they work the same after a GUI flash.

## Monsieur Cuisine Smart (SKMS 1200): in test

Toque drives the Smart since 0.8.0, but the project has no Smart: **Smart owners test it and report
in [issue #59](https://github.com/Wuki60/MrCuisineVibeConnect/issues/59)**. Tell the person so before
starting. The full guide, with the tests to run, is [docs/SMART.en.md](docs/SMART.en.md).

1. **Root.** Toque has no root guide or script for the Smart. The tablet must be rooted with Magisk,
   with ADB enabled, by following
   [Camybrith Cooking's guide](https://github.com/Tidusneo24150/camybrith-cooking/blob/main/docs/GUIDE.en.md).
   Read it first and keep this file's rules: backup before anything is written, ask before each step
   that writes to the robot, never type a password. If the robot is already rooted, skip this.
2. **Check** the robot over ADB: `adb shell getprop ro.build.version.release` gives `8.1.0`, and
   `adb shell "su -c 'id -u'"` gives `0` (the person may have to tap **Grant** in Magisk). Otherwise
   stop.
3. **Download** `Toque-smart-X.Y.Z.apk` from the
   [latest release](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest): the asset whose
   name **starts with `Toque-smart-`** (about 1.1 MB). Ask before downloading.
4. **Install**, from the repository folder:
   `powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1 -Apk <folder>\Toque-smart-X.Y.Z.apk`.
   It installs the APK, sets up root for Toque (`tools/robot/setup-root.sh`) and starts it. If the
   install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, another port with the same package name
   (`app.toque`, for example Camybrith Cooking's Ratatouille Edition) is installed with another
   signature: `adb uninstall app.toque` removes it, **with its settings and own recipes**. Ask the
   person first, and suggest exporting those recipes in that app.
5. **On the robot**: *Which robot is this?* → **Monsieur Cuisine Smart (SKMS 1200)**.
6. **First run, empty bowl, hand on the main switch**: Settings › Machine › Real machine › Apply.
   Then the tests of [docs/SMART.en.md](docs/SMART.en.md), in their order. Help the person write
   what they saw for #59; they post it.

The *Known problems* table below is for the Connect. On a Smart, describe the problem in #59.
Undo: Toque › Settings › Tools › *Back to the stock app* (it re-enables the stock app's
`SerialPortMC30Service`), or `tools\restore-robot.ps1 -Uninstall`.

## Known problems and fixes (Connect)

| Symptom | Cause | Fix |
|---|---|---|
| SP Flash Tool waits forever, nothing in Device Manager | A↔C cable, wrong port, or USB-C port on the PC | A↔A cable, right-hand "Android USB" port, a USB-A port on the PC |
| Windows: *"A driver can't load on this device: usb2ser.sys"* | Memory integrity on | Person turns it off (Windows Security › Device security › Core isolation), restarts; `driver` again |
| Robot shows a battery picture instead of booting | Switched on with the cable plugged in | Off, unplug, on, plug in after start-up |
| `adb` shows nothing in TWRP | Cable plugged before TWRP was up, or driver missing | Unplug/replug; `status` |
| `root` fails during the Magisk patch | Not the original boot, or a corrupt backup | Nothing was flashed. Check `C:\Toque\backup`, redo `backup` |
| `android`: `su` returns nothing | The Magisk prompt was missed (it closes after 10 s and then refuses) | Person taps **Grant** quickly; if refused for good: Magisk app › Superuser › Shell › allow |
| Toque shows "Base absente" / no motor link | Magisk busybox missing | `install` again (it runs `tools/robot/setup-root.sh`) |
| After every restart Magisk asks again to allow Toque; left unanswered, Toque shows "Base absente" and its journal repeats *Accès root refusé* until the next restart | Magisk 31 forgets the permission of every app each time the robot starts (Android 6), and this robot was installed before `install` added the boot script that gives Toque's back | `install` again: it writes `/data/adb/service.d/toque-su.sh`, which allows Toque at every start |
| Stuck in the stock app (no Home/Back buttons on this robot) | Normal | Floating **Back to Toque** button, or `adb shell am start -n app.toque/.ui.MainActivity` |
| `Toque s'est vu refuser les droits` toasts | Magisk policy | `install` again (it allows Toque in Magisk's database) |
| `root`: `can't create /proc/self/fd/` and `Failed to patch` after `Loading dtbs` | Magisk's installer messages outside its UI; no fstab in the device tree on Android 6 | Harmless if the step ends with `== Done` |
| `android`: `WARNING` that the boot partition could not be checked (older script) | The check used Magisk's busybox, which only exists after `install` | Harmless; the step now reads the partition back and hashes it on the PC |
| TWRP restarts by itself, the link drops and the robot stops on the battery screen | This TWRP restarts about 6 min 40 s after it started (watchdog) | Restart TWRP (off, unplug, on, plug in) and run `root` right away: it takes about 20 s. The step refuses a TWRP that has run more than 5 min |
| TWRP's first screen: *Keep Read Only* / *Swipe to Allow Modifications* | TWRP protects `/system` by default | Swipe to allow modifications. Otherwise `root` stops with "/system is read-only" |
| `prepare`: SP Flash Tool checksum mismatch, file sizes right | The RAR was extracted without its password: some tools write garbage of the right size | Extract again with the password `spflashtool.com` and check that no error is reported |
| Magisk app: *Requires additional setup*, then *reinstall Magisk… recovery mode cannot get device info* | Magisk app's own check, harmless here | Accept a reboot if offered; **never** use *Direct install* (it rewrites `boot`). Back out of the install screen |
| Toque says "bowl not detected" with the bowl in place, and "detected" without it | The bowl sensor is reversed on many robots (B2, C3, SKMC 1100) | Settings › Safety › **Invert the bowl sensor**; check *Settings › Tools › Diagnostics*, then enable *Require bowl detection* |
| The rotary knob does nothing | Knob keys not set up | Tap the *Rotary knob not set up* pill on Home and follow the 3 steps |
| Scale shows "overload · 0 g" right after the install | The robot was resting on the cable or the open cover | Put the robot flat on its feet with nothing under it, screw the cover back, restart it |

## Undo (Connect)

- Back to the stock app, keeping root: Toque › Settings › Tools › *Back to the stock app*, or
  `powershell -ExecutionPolicy Bypass -File tools\restore-robot.ps1 -Uninstall`.
- Remove root and TWRP: SP Flash Tool *Download Only* with **boot** =
  `C:\Toque\backup\boot-original.img` and **recovery** = `C:\Toque\backup\recovery-original.img`.

## After the install

- Updates: Toque › Settings › Updates installs new releases from this repository, each robot its
  own APK.
- Recipes: Settings › Lidl recipes › Sync (monsieur-cuisine.com, no account). Nightly re-sync.
- Clock: set automatically (time zone from the Internet connection); Settings › Date and time.
- To work on the robot later over Wi-Fi: `adb connect <robot IP>:5555`.

## Working on the code

If you were asked to change Toque rather than install it: `core/` is pure Kotlin (no Android) and
holds the protocol, the safety rules, the controller and the recipe parsing; `app/` is the Android
app (minSdk 23, no AndroidX, views built in code). Run `gradlew :core:test` (unit tests, including a
check that every interface text has an English translation in `app/src/main/assets/lang/en.json`)
and `gradlew :app:assembleRelease` (the Smart's APK: `gradlew :app:assembleRelease -PtoqueSmart=true`).
Each robot has a profile in `core/.../robot/Robots.kt` (serial port, protocol, programs,
temperatures, Lidl recipe type) and its own codec (`ConnectCodec`, `SmartCodec`). Interface texts are
written in French and wrapped in `tr(...)`.
Motor safety rules live in `core/.../machine/Safety.kt` and are enforced by `MachineController`:
keep them strict, and add a test in `ControllerTest` for any behaviour change.
