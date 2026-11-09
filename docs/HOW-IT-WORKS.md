# How sevOS works

Findings below come from an HY300 Pro Upgrade (`rk3326_sgo`). Other boards may differ.

## Hardware

| | |
|---|---|
| SoC | RK3326, 4 cores up to 1416 MHz, Mali-G31 |
| Memory | 2 GB RAM, about 8 GB eMMC |
| Software | Android 12 (API 32), 32-bit userspace, 64-bit kernel 4.19 |
| Display | 1280x720, density 240 |
| Video decoders | H.264, HEVC, VP8. No hardware VP9, so prefer H.264 for streaming |
| Sensor | Accelerometer only, no distance sensor |
| Focus | Manual, driven by a stepper on GPIO 113 to 116 |

## Installing without flashing tools

System partitions are ordinary ext4 images inside a dynamic `super` partition, exposed while running as `/dev/block/mapper/<name>`.

The installer edits a copy with `debugfs`, keeps the size identical, and compares it against the original in 4 KiB blocks. Only the differing blocks are written, typically a few hundred, and each partition's hash is checked afterwards. This needs no partition offsets, so it does not depend on one board's layout.

Files removed from an image stay as unused bytes in the partition until overwritten. They are no longer reachable or executed.

## Keystone

Keystone lives in the vendor-modified `/system/bin/surfaceflinger` and `/system/lib/librkgfx_dc.so`, which is why a generic system image cannot replace them.

Write the corners to `persist.sys.keystone.{lt,rt,lb,rb}`, then toggle `persist.sys.keystone.update` from 0 to 1. Mirroring for ceiling and rear mounting uses `persist.sys.keystone.mirror_{x,y}`. Automatic correction turns accelerometer tilt into corner offsets.

## Instant standby

`sevos_boot.sh` holds a kernel wake lock so the device never suspends. When the screen state changes, `sevos.rc` switches `Lcd_power`, which cuts the light and the fan at once. An overlay sets the power key to sleep without the vendor menu.

The device stays awake in standby, so it keeps drawing a small amount of power.

## Fan

The board has no fan controller or driver. The fan shares the `Lcd_power` line with the light, and it responds to very short interruptions of that line while the image stays stable.

`sevos_fan.sh` opens that line once and toggles it at 120 Hz. `sys.sevos.fan` holds the power in percent, from 10 to 100, and resets to full speed on every boot.

| Setting | Result on the test unit |
|---|---|
| 100% | Factory behavior |
| About 80% | Passed a 20 minute heat test, SoC 44 to 46 °C |
| About 65% | Clean, not soak tested |
| 40 to 50% | Audible crackle over longer runs |
| Below 40% | The fan may stall |
| 2 to 3 kHz | The fan stalls and whines |

Things that matter when changing it:

- **Less airflow means less cooling, and LED temperature cannot be measured on this board.** Only the SoC is guarded: above 60 °C the script returns to full speed. Below 80% is untested for long-term LED life, and the launcher warns about it.
- Brightness may change with the fan level. It has not been measured.
- The switching loop may use only shell builtins, at `SCHED_FIFO` priority. Forking a process during the loop makes the fan surge.
- Android init delays a service `restart` by up to 5 seconds, which would leave the line off. The script therefore runs permanently and is signalled through a flag file.

## Boot animation

`/product/media/bootanimation.ts` takes precedence over `/system/media/bootanimation.zip`, so it is removed. The vendor library waits a fixed 6 seconds before the animation starts.

## Idle CPU

The vendor focus motor driver's `motor_run` kernel thread uses roughly 12% of one core while idle. Each loop calls `gsv(0,0,0,0)` twice, and that function busy-waits for 1 ms after setting the GPIOs. No value written to `/sys/class/motor_cc/motor_cc/motor` stops it, so fixing it requires patching the kernel. sevOS leaves the kernel unmodified.

## Network ADB

Factory firmware enables root ADB on TCP port 3268 through `service.adb.tcp.port` in `/system/build.prop`. sevOS removes that line unless installed with `--network-adb`.
