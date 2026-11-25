# Installing Toque on the Monsieur Cuisine Connect from a Mac

🇫🇷 **[Version française](MACOS.md)**

> **With an AI agent** (Claude, ChatGPT…) that operates your Mac: give it the repository link and
> ask it to follow [AGENTS.md](../AGENTS.md). The script
> [`tools/setup/toque-setup.sh`](../tools/setup/toque-setup.sh) automates every step below
> (`bash tools/setup/toque-setup.sh status` shows where you are).

On macOS, SP Flash Tool and its Windows driver are replaced by
[mtkclient](https://github.com/bkerler/mtkclient) for the two operations that need MediaTek download
mode (`backup` and `flash-twrp`), and by [`tools/robot/twrp-flash.sh`](../tools/robot/twrp-flash.sh)
to write `boot`/`recovery` from TWRP (`flash-root` and `unroot`). Done end to end on a real SKMC
1200 C3 from an Apple silicon Mac in November 2026, using the serial-driver fixes in
[`tools/setup/mtkclient-macos-serial.patch`](../tools/setup/mtkclient-macos-serial.patch) applied by
`prepare`.

For the shared post-install sections (first run with an empty bowl, startup behaviour, Lidl
recipes), see [docs/INSTALLATION.md](INSTALLATION.md#4-first-run--empty-bowl).

## What you need

- A Mac (Apple silicon or Intel) with [Homebrew](https://brew.sh): `brew install python libusb`
  (Python 3.10+), and git (`xcode-select --install`). No driver to install.
- A **USB-A male / USB-A male** data cable and a small Torx screwdriver. A USB-A ↔ USB-C cable
  does **not** work: on a Mac with only USB-C ports, use a **USB-C to USB-A adapter** (female
  USB-A) or a USB hub between the Mac and the A↔A cable.

> **Power-on rules.** Switch the robot on with the cable **unplugged** for adb steps (`root`,
> `flash-root`, `android`, `install`, `wifi`); plug the cable in once the screen is up (switched on
> with the cable in, it stops on a battery screen). Download-mode steps (`backup`, `flash-twrp`) are
> the exception: start with robot **off** and cable **plugged in**, then switch it on when asked.
>
> On Apple silicon, click **Allow** if macOS asks *"Allow accessory to connect?"*; in download mode,
> switch the robot off and on again afterwards.

## 1. Prepare, backup and TWRP

1. `bash tools/setup/toque-setup.sh prepare` downloads adb, Magisk v31.0, TWRP, the scatter file,
   the latest Connect APK (`Toque-X.Y.Z.apk`), and pinned mtkclient v2.1.4.1 (with its macOS serial
   fixes and SHA-256-pinned Python packages) into `~/Toque`.
2. Robot off (rear switch), maintenance cover unscrewed, cable **only in the right-hand port
   labelled "Android USB"** (the other port can destroy the board).
3. `bash tools/setup/toque-setup.sh backup`, then switch the robot on when mtkclient waits for the
   device. *Handshake failed, retrying...* every 10 s while waiting is normal. It saves and verifies
   `~/Toque/backup/boot-original.img` and `recovery-original.img` (16 MB each): **copy `~/Toque/backup/`
   to a USB stick or cloud — it is your only way back.** After each mtkclient session the robot
   stays in download mode (black screen): switch it off before the next step.
   - **SKMC 1100 A1 and SKMC 1200 D4** (board `spm188a`): run `bash tools/setup/toque-setup.sh own-twrp`
     before `flash-twrp` to build `~/Toque/twrp-own-kernel.img` around your robot's own kernel.
     `flash-twrp` reads the board from the backup and stops, without writing anything, if it is
     needed and missing.
4. `bash tools/setup/toque-setup.sh flash-twrp`: writes TWRP into `boot` only and reads it back.
5. Switch the robot off, unplug the cable, switch it on → **TWRP** starts. Plug the cable back in.

### If mtkclient does not reach the robot

- Check the A↔A cable, the right-hand port and the USB-C → USB-A adapter; right after power-on,
  `system_profiler SPUSBDataType` should list **MT65xx Preloader** (vendor ID `0x0e8d`).
- On *Sync error* or *Failed to upload da*: run `prepare` (`status` must show *mtkclient: v2.1.4.1
  + macOS serial fixes*), switch the robot off, and retry (`--libusb` tries libusb instead).
- Fallback: do only `backup` and `flash-twrp` on a Windows PC ([INSTALLATION.md](INSTALLATION.md)),
  copy the two backups into `~/Toque/backup/`, run `backup` on the Mac to verify them, and continue
  below (all remaining steps use adb only).

## 2. Root, Android and Toque

1. In TWRP: `bash tools/setup/toque-setup.sh root` (patches original `boot` with Magisk, enables
   ADB, authorizes `~/.android/adbkey.pub`).
2. Still in TWRP: `bash tools/setup/toque-setup.sh flash-root` (writes TWRP into `recovery` and
   `boot-magisk.img` into `boot` via `twrp-flash.sh`, verifying both before and after; do not switch
   off while it runs).
3. Switch off, unplug, switch on → stock app starts. Plug the cable in.
4. `bash tools/setup/toque-setup.sh android` (installs Magisk app; tap **Grant** on the robot when
   prompted; checks the boot partition).
5. `bash tools/setup/toque-setup.sh install` (or `bash tools/install-robot.sh [--apk FILE] [--ip IP]`),
   then on the robot (**empty bowl**, lid closed, hand near the main switch):
   **Settings › Advanced › Machine › Real machine › Apply**, and re-run `install` to confirm hardware mode.
6. Optional: `bash tools/setup/toque-setup.sh wifi` to enable ADB over Wi-Fi (port 5555, home
   network only) so you can remove the cable and close the cover.

Continue with [First run — EMPTY bowl](INSTALLATION.md#4-first-run--empty-bowl).

## Going back to the stock app / unroot

- Keep root, restore stock app: Settings › Advanced › Back to the stock app, or
  `bash tools/restore-robot.sh --uninstall`.
- Remove root and TWRP (run the line above first): `bash tools/setup/toque-setup.sh unroot` (from
  TWRP; add `--download-mode` if the robot no longer boots).
