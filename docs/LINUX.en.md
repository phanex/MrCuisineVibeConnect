# Installing Toque from Linux (Connect)

🇫🇷 **[Version française](LINUX.md)**

Same procedure and checks as on macOS ([MACOS.en.md](MACOS.en.md)), with the same script,
`tools/setup/toque-setup.sh`: [mtkclient](https://github.com/bkerler/mtkclient) does the two steps
that need MediaTek download mode (`backup`, `flash-twrp`), TWRP writes the rest, no driver and no
SP Flash Tool.

**Tested** end to end on 27 November 2026 from **Fedora 44** (x86_64, Python 3.14) on a real robot
labelled SKMC 1200 whose board is `spm188a` (SKMC 1100 A1 board, see #111 / #115): `prepare`,
`backup`, `own-twrp`, `flash-twrp` (TWRP starts, touch works), `root`, `flash-root`, `android`,
`install`, then the first run (manual cooking, empty bowl) and the Lidl recipe sync. mtkclient went
through **libusb** (the Linux default) and reached the preloader on the first try; the macOS serial
fixes were not needed. Other distributions: the script prints the equivalent package command, not
tested.

## One-time setup (sudo)

```sh
# Fedora (Debian/Ubuntu/Arch: 'toque-setup.sh prepare' prints the equivalent)
sudo dnf install -y libusb1 python3 git unzip curl android-tools
sudo cp tools/setup/linux/51-toque-mtk.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules && sudo udevadm trigger
```

The rule gives your user access to the MediaTek USB device (vendor `0e8d`) and tells ModemManager
(which ships its own MediaTek rules and would otherwise grab the port) to ignore it. Without it:
`sudo systemctl stop ModemManager` until the download-mode steps are done.

## Steps

From the repository folder, each step when the previous one says so (`status` shows where you are):

```sh
bash tools/setup/toque-setup.sh prepare      # downloads, checks SHA-256, installs mtkclient in ~/Toque
bash tools/setup/toque-setup.sh backup       # robot OFF, cable in, start the step, then switch the robot ON
bash tools/setup/toque-setup.sh own-twrp     # SKMC 1100 A1 / spm188a boards (flash-twrp says so)
bash tools/setup/toque-setup.sh flash-twrp   # same power sequence as backup
bash tools/setup/toque-setup.sh root         # TWRP on screen, cable plugged in after it started
bash tools/setup/toque-setup.sh flash-root
bash tools/setup/toque-setup.sh android      # tap Grant on the robot within 10 s
bash tools/setup/toque-setup.sh install
```

`flash-twrp` reads the board from the boot backup and refuses to write the guide's TWRP on a
`spm188a` board until `own-twrp` has been run. **Keep a copy of `~/Toque/backup/`**: it is the way
back.

Cable: USB-A male to USB-A male into the **"Android USB"** port (right-hand side). A PC with only
USB-C ports: a USB-C to USB-A adapter on the PC side, never a USB-C to USB-A cable.

## If mtkclient does not reach the robot

1. `lsusb | grep 0e8d` right after switching the robot on should show `0e8d:2000` (preloader) for a
   few seconds. Nothing: cable, port or hub. (`0e8d:2008` is the robot running Android, in MTP mode:
   switch it off first.)
2. `ls /dev/ttyACM*` appears but mtkclient stays silent: retry with `--serial` (mtkclient through the
   serial port, with the fixes of `mtkclient-macos-serial.patch`).
3. `journalctl -f` while plugging: ModemManager probing the port means the udev rule is missing.
4. After a session the robot stays in download mode (black screen): switch it OFF before the next
   download-mode step.
