# sevOS

*Dedicated to my spouse, whom friends and family call Şevo. That is where the name comes from. Proudly vibecoded.*

A clean system and launcher for RK3326 projectors running Android 12, built around the HY300 Pro Upgrade. It removes the vendor launcher, ad and update services and leaves a fast home screen that works with a remote.

sevOS does not ship any vendor file. The installer copies the partitions from **your own projector**, builds sevOS from them on your computer, and writes back only what changed.

> **Türkçe:** Arayüz şimdilik yalnızca Türkçe. Adım adım kurulum için [docs/KURULUM.md](docs/KURULUM.md).

![sevOS boot animation](media/sevos_boot_preview.gif)

## Status: experimental

Tested on one unit: HY300 Pro Upgrade, `rk3326_sgo`, Android 12, 2 GB RAM, 1280x720.

- The resulting system runs daily on that unit.
- The build step was checked offline against that unit's factory partitions: the output matches the running system file for file, apart from the options described below.
- **The full install flow has not yet been run from start to finish on a factory device.** Have a way to recover first, see [docs/RECOVERY.md](docs/RECOVERY.md).

Other RK3326 projectors may work. The installer refuses a device that lacks what sevOS depends on.

## What it does

| | |
|---|---|
| **Launcher** | Remote-first home screen with apps, HDMI input, media, settings and memory cleanup. No ads, no network permission. |
| **Projector panel** | Four-corner keystone, automatic keystone from the accelerometer, ceiling and rear mounting, color. |
| **Fan speed** | A slider and presets for a fan the board has no controller for. See [how](docs/HOW-IT-WORKS.md#fan). |
| **Instant standby** | The power key turns off the light and the fan immediately. Waking up has no resume delay. |
| **Fewer background apps** | Vendor launcher, OTA, ad, test and screen-cast apps, a vendor daemon and the Google services are not in the system. |
| **Less work at boot** | The vendor recovery self-check that reads 88 MB of eMMC during startup is stopped. |

**Why:** these projectors have capable hardware but ship with an ad-filled launcher and closed background services that open outbound connections and cannot be audited. The exact list of what gets removed is in [`manifest/`](manifest).

**What stays:** bootloader, kernel, `vendor` drivers and the vendor-modified SurfaceFlinger. Keystone, HDMI input and the remote depend on them, and no source is available. A generic Android image would lose keystone.

## Kicked out

**The factory system on our test unit was running what looks like residential proxy malware. Your projector, your internet connection, someone else's traffic.**

sevOS removes **572 MB of factory software**, frees **470 MB of RAM** and closes the doors it left open.

### The hidden passengers

None of these are in the system image. They lived in `/data`, ran in the background, and took 96 MB of RAM from a 2 GB projector.

| RAM | Process | What we found |
|---:|---|---|
| 30 MB | `seal` | Runs as the **system user** from a hidden folder. Loads an embedded payload, keeps encrypted task files, and was connected to an **Alibaba Cloud** server. |
| 23 MB | `petter_service` | A jar hidden in `/data/system/tasks`, also the system user. Its strings say `proxy client` and `max proxy instances`. Live connection to port 14101. |
| 16 MB | `xzhera` | A 4.6 MB packed binary with no business being there. |
| 10 MB | `com.rockchip.itvbox` | **7 live connections at once**, mostly to ports 10001 to 10007. Unpacks the others on boot, with fake 2018 timestamps. Signed with the firmware's platform key. |
| 10 MB | `kprn` (two of them) | Dropped into the same hidden folder. |
| 8 MB | `aigogo0713` | The proxy client itself: `add_tcp_proxy`, `openTunnel`. Uses encrypted DNS, which walks straight past your router's DNS filter. |

These findings come from static analysis of the files plus a live connection snapshot. Traffic contents were not captured, and what originally started them was never found. After the rebuild and the `/data` wipe, none of them remained, and a passive network log showed only AirPlay, DHCP and ADB. This describes one unit. Check yours.

### The factory bloat

| Disk | RAM | What | Why it's gone |
|---:|---:|---|---|
| 88 MB | 48 MB | `QuickShare-Projector` | Screen casting with **12 TCP ports listening, 10 of them open to your whole network**. More open doors than a hotel. |
| 70 MB | 112 MB | `PrebuiltGmsCorePano` | Google Play Services, five processes deep, on a projector. |
| 13 MB | 57 MB | `txcz_launcher` | The vendor launcher, ad activities included, talking plain HTTP to an **Alibaba Cloud** server. |
| 11 MB | 22 MB | `TxczSetting` | Vendor settings, replaced by the projector panel. |
| 5 MB | 14 MB | `txczbox` | Vendor app with install code inside. |
| 4 MB | 13 MB | `txcz_ota` | The vendor's remote update channel. |
| 10 MB | 4 MB | `daemon12138` | A daemon with a number for a name, started on every boot. |
| 6 MB | 5 MB | `RKDeviceTest` | A factory test tool, still running in your living room. |
| 2 MB | 5 MB | `Oranth` | Privileged vendor accessibility service. |
| <1 MB | 5 MB | `SecureElement` | Not needed here. |
| 363 MB | – | `netflix`, `YouTube_for_Android_TV`, `Xuper`, `Tubesky` (the Play Store with a fake name), `txcztest`, `CellBroadcastAppPlatform` (emergency phone alerts, on a projector), `txcz_app`, `GoogleServicesFramework`, `GoogleOneTimeInitializer`, `profcollectd`, the vendor boot video | Nobody asked for any of it. |

Disk sizes come from the factory images. RAM is PSS from one idle snapshot.

### Before and after, test unit

| | Factory | sevOS |
|---|---:|---:|
| Available RAM at idle | 721 MB | **1,191 MB** |
| Suspicious background processes | 7 | **0** |
| TCP ports open to the network | 11 | **ADB and AirPlay only**, both chosen by the owner |

ADB is off by default in the installer, and AirPlay is an optional app.

Every removed path is listed in [`manifest/`](manifest).

## Install

Türkçe rehber: [docs/KURULUM.md](docs/KURULUM.md)

**This erases the apps and settings on the projector.** The original system partitions are copied to your computer first.

### 1. Prepare your computer

You need `python3`, `adb`, `e2fsprogs` (`debugfs` and `e2fsck`) and about 3 GB of free disk.

```bash
# macOS
brew install android-platform-tools e2fsprogs

# Debian or Ubuntu
sudo apt install adb e2fsprogs python3
```

Then download this repository and open a terminal in its folder.

### 2. Connect to the projector

Choose one. Both use the same installer.

#### Over the network

The projector and the computer must be on the same network. Find the projector's IP address in its network settings, then:

```bash
adb connect 192.168.1.50:3268
adb -s 192.168.1.50:3268 shell su 0 id
```

Factory firmware on the test unit listens for root ADB on port 3268. If the port differs on your unit, use yours. The second command must print `uid=0(root)`.

Install with:

```bash
python3 installer/install.py --device 192.168.1.50:3268
```

#### With a USB cable

Connect the projector's USB port to the computer with a USB-A to USB-A cable, then:

```bash
adb devices
```

If the projector is listed, use the serial number it shows:

```bash
adb -s SERIAL shell su 0 id
python3 installer/install.py --device SERIAL
```

If it is not listed, your unit's USB port is in host mode while Android runs. This is the case on the test unit, and there the network route is the way to install. The Loader mode reached over USB is not enough on its own: the vendor Loader on the test unit cannot read partitions above 32 MiB, so it cannot copy the system from the device.

Do not unplug the cable or the power while the installer is writing.

### 3. Run the installer

The installer first checks the device and stops without changing anything if it is not compatible. Type `INSTALL` when asked, then it:

1. Copies `system`, `system_ext`, `product` and `odm`, about 1.5 GB, into `sevos-work/stock/` and verifies each one.
2. Builds the new images on your computer.
3. Writes only the blocks that changed, a few megabytes in total, and verifies each partition.
4. Wipes `/data`, because vendor apps keep their data and updates there.
5. Reboots.

**Keep `sevos-work/stock/`.** It is your way back, see [recovery](docs/RECOVERY.md).

The first boot takes a few minutes. When the home screen appears, connect to Wi-Fi from **Ayarlar** (Settings).

### Options

| Option | Effect |
|---|---|
| `--network-adb` | Keeps root ADB open on the network after install. **Anyone on your network can then control the device.** Needed if you want to install apps over the network afterwards. |
| `--no-wipe` | Keeps `/data`, including the vendor apps' leftovers. |
| `--from-dir DIR` | Builds from `DIR/<partition>.img` and touches no device. |
| `--work DIR` | Uses another folder for the backup and the build. |
| `--force` | Skips the compatibility checks. Only use this if you can recover over USB. |

### Troubleshooting

| Message | What to do |
|---|---|
| `No route to host`, while the projector is on the network | Run `adb kill-server`, then `adb start-server`, and connect again. |
| `no root over ADB` | This firmware does not allow `su 0` over ADB. The installer cannot work on it. |
| `this does not look like a supported projector` | The listed parts are missing. sevOS would lose functions there. |
| `the copy of ... does not match the device` | Nothing was changed. Run the installer again on a stable connection. |
| `writing ... failed` | Do not reboot. Read [recovery](docs/RECOVERY.md) first. |
| `sevOS is already installed` | The installer only runs on factory firmware. |

## Apps

sevOS ships no third-party apps. Install what you want, for example:

- [SmartTube](https://github.com/yuliskov/SmartTube) for YouTube, `armeabi-v7a` build.
- [android-airplay-server](https://github.com/jqssun/android-airplay-server) for iPhone and Mac mirroring. On low-RAM profiles Android rejects its picture-in-picture call, so it may need patching. Protected content does not play over it.

Protected streaming apps are limited here. For Netflix, Disney+ and similar services, connect a certified streaming device to HDMI.

## Build the launcher

Android SDK 32, JDK 17 and Gradle 8 are required.

```bash
cd launcher
gradle assembleRelease
```

To use your own build, sign it and replace `prebuilt/CemLauncher.apk` before installing. Add a `keystore.properties` file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword` to sign automatically. The internal package name is `os.cemkuck.launcher`.

## Layout

| Path | Contents |
|---|---|
| `installer/` | The installer and the on-device block writer |
| `manifest/` | Files removed from each partition |
| `system/` | Init service and shell scripts installed into the system |
| `launcher/` | Launcher source (Kotlin, no AndroidX) |
| `overlay/` | Overlay that makes the power key go straight to sleep |
| `media/` | Logo and boot animation |
| `prebuilt/` | Built launcher and overlay |
| `docs/` | [How it works](docs/HOW-IT-WORKS.md), [recovery](docs/RECOVERY.md), [Turkish install guide](docs/KURULUM.md) |

## License

GPL-3.0, see [LICENSE](LICENSE). This covers the code and media in this repository. It does not cover the vendor software on your device.

HY300 is used only to describe compatible hardware. This project is not affiliated with any projector manufacturer.

There is no warranty. This modifies system partitions and can leave a device unbootable.
