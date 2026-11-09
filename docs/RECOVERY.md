# Recovery

sevOS never writes the bootloader, kernel or `vendor`. If the system fails to boot, the Rockchip Loader is still available over USB.

Read this before installing. You need:

- `sevos-work/stock/` from the installer, which holds your original partitions and `layout.txt`.
- [`rkdeveloptool`](https://github.com/rockchip-linux/rkdeveloptool).
- A USB-A to USB-A cable, for the projector's USB port.

**Do not write the `uboot`, `trust` or `security` partitions.** Recovery depends on them.

## Enter Loader mode

If ADB still works:

```bash
adb shell su 0 reboot loader
```

If Android is not running:

1. Unplug the mains.
2. Unplug the USB cable and wait 5 seconds. USB power keeps a frozen processor alive, so this step is required.
3. Plug in USB. The projector starts from the computer's power and enters Loader within about a second.

Check it with:

```bash
rkdeveloptool ld
```

It should list a Loader device, `2207:330d` on the test unit.

## Restore the original partitions

`layout.txt` records where each partition sits inside `super`. Find the start of `super` from `rkdeveloptool ppt` and add each partition's offset to get its absolute sector. Then:

```bash
rkdeveloptool wl <start-sector> sevos-work/stock/system.img
```

Repeat for `system_ext`, `product` and `odm`, then plug in the mains before leaving Loader:

```bash
rkdeveloptool rd
```

If USB is connected without mains, the projector returns to Loader instead of booting.

After restoring, erase `/data` from recovery so that the system and its data match.

## Notes

On the test unit, the vendor Loader could not read above 32 MiB, returning `0xCC` bytes. Writing worked, so verify a restored partition after boot with `sha256sum`.
