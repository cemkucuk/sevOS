#!/usr/bin/env python3
"""sevOS installer.

Reads the system partitions from your own projector over ADB, builds sevOS from
them on your computer, writes back only the blocks that changed, wipes user
data and reboots. No vendor file is downloaded or redistributed.

    python3 installer/install.py --device 192.168.1.50:3268

See README.md first. This erases the apps and settings on the projector.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARTITIONS = ['system', 'system_ext', 'product', 'odm']
BLOCK = 4096
DEVICE_TMP = '/data/local/tmp'
LABEL = 'selinux_label'

# (partition, directory inside the image, file name, source in the repository, mode)
FILES = [
    ('system', '/system/etc/init', 'sevos.rc', 'system/sevos.rc', '0100644'),
    ('system', '/system/etc', 'sevos_boot.sh', 'system/sevos_boot.sh', '0100755'),
    ('system', '/system/etc', 'sevos_ctl.sh', 'system/sevos_ctl.sh', '0100755'),
    ('system', '/system/etc', 'sevos_fan.sh', 'system/sevos_fan.sh', '0100755'),
    ('system', '/system/priv-app/CemLauncher', 'CemLauncher.apk', 'prebuilt/CemLauncher.apk', '0100644'),
    ('system', '/system/media', 'bootanimation.zip', 'media/bootanimation.zip', '0100644'),
    ('product', '/overlay/SevPower', 'SevPower.apk', 'prebuilt/SevPower.apk', '0100644'),
]
NEW_DIRS = {
    'system': ['/system/priv-app/CemLauncher'],
    'product': ['/overlay', '/overlay/SevPower'],
}
# Things sevOS drives directly. Without them the launcher or the fan control has nothing to talk to.
REQUIRED_ON_DEVICE = ['/sys/class/leds/Lcd_power/brightness', '/system/lib/librkgfx_dc.so']


def die(message):
    sys.exit(f'\nStopped: {message}')


def find_tool(name, env):
    if os.environ.get(env):
        return os.environ[env]
    found = shutil.which(name)
    if found:
        return found
    for prefix in ('/opt/homebrew/opt/e2fsprogs/sbin', '/usr/local/opt/e2fsprogs/sbin', '/sbin', '/usr/sbin'):
        candidate = os.path.join(prefix, name)
        if os.path.exists(candidate):
            return candidate
    die(f'{name} was not found. Install e2fsprogs, or set {env} to its full path.')


class Device:
    def __init__(self, adb, serial):
        self.adb = [adb] + (['-s', serial] if serial else [])

    def shell(self, command, check=True):
        result = subprocess.run(self.adb + ['shell', f"su 0 sh -c '{command}'"],
                                stdin=subprocess.DEVNULL, capture_output=True, text=True, errors='replace')
        if check and result.returncode != 0:
            die(f'device command failed: {command}\n{result.stdout}{result.stderr}')
        return result.stdout.strip()

    def push(self, local, remote):
        subprocess.run(self.adb + ['push', local, remote], stdin=subprocess.DEVNULL, capture_output=True, check=True)

    def pull_partition(self, name, target):
        path = f'/dev/block/mapper/{name}'
        size = int(self.shell(f'blockdev --getsize64 {path}'))
        expected = self.shell(f'sha256sum {path}').split()[0]
        print(f'  {name}: {size // 1048576} MB')
        with open(target, 'wb') as out:
            subprocess.run(self.adb + ['exec-out', f"su 0 sh -c 'dd if={path} bs=1048576 2>/dev/null'"],
                           stdin=subprocess.DEVNULL, stdout=out, check=True)
        if os.path.getsize(target) != size or sha256(target) != expected:
            die(f'the copy of {name} does not match the device. Try again, a cable or a stable network helps.')


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1048576), b''):
            digest.update(chunk)
    return digest.hexdigest()


def check_device(device, force):
    if 'uid=0' not in device.shell('id', check=False):
        die('no root over ADB. This firmware does not expose "su 0".')
    if device.shell('getprop ro.sevos.version', check=False):
        die('sevOS is already installed on this device.')
    sdk = device.shell('getprop ro.build.version.sdk')
    platform = device.shell('getprop ro.board.platform')
    print(f'  Android API {sdk}, platform {platform}')
    problems = []
    if sdk != '32':
        problems.append(f'expected Android 12 (API 32), found API {sdk}')
    if not platform.startswith('rk3326'):
        problems.append(f'expected an RK3326, found {platform}')
    for path in REQUIRED_ON_DEVICE:
        if device.shell(f'[ -e {path} ] && echo yes', check=False) != 'yes':
            problems.append(f'{path} is missing')
    for name in PARTITIONS:
        if device.shell(f'[ -e /dev/block/mapper/{name} ] && echo yes', check=False) != 'yes':
            problems.append(f'partition {name} is missing')
    if problems:
        text = '\n  - '.join(problems)
        if not force:
            die(f'this does not look like a supported projector:\n  - {text}\nUse --force only if you can recover the device over USB.')
        print(f'  Ignoring because of --force:\n  - {text}')


def edit_build_prop(text, network_adb):
    lines = []
    for line in text.split('\n'):
        if line.startswith('ro.build.display.id='):
            line = 'ro.build.display.id=sevOS 1.0'
        elif line.startswith('service.adb.tcp.port=') and not network_adb:
            continue
        lines.append(line)
    while lines and lines[-1] == '':
        lines.pop()
    return '\n'.join(lines) + '\n\n# sevOS\nro.sevos.version=1.0\n'


def install_commands(directory, name, source, mode):
    return [
        f'cd {directory}',
        f'rm {name}',
        f'write {source} {name}',
        f'sif {name} mode {mode}',
        f'sif {name} uid 0',
        f'sif {name} gid 0',
        f'ea_set -f {LABEL} {name} security.selinux',
    ]


def build_partition(part, image, stage, tools, network_adb):
    debugfs, e2fsck = tools
    commands = []
    with open(os.path.join(ROOT, 'manifest', f'remove-{part}.txt')) as f:
        for line in f:
            kind, path = line.rstrip('\n').split(' ', 1)
            commands.append(f'{"rmdir" if kind == "d" else "rm"} "{path}"')
    for directory in NEW_DIRS.get(part, []):
        commands += [f'mkdir {directory}', f'sif {directory} mode 040755', f'sif {directory} uid 0',
                     f'sif {directory} gid 0', f'ea_set -f {LABEL} {directory} security.selinux']
    written = []
    for file_part, directory, name, source, mode in FILES:
        if file_part != part:
            continue
        local = f'{part}_{name}'
        shutil.copy(os.path.join(ROOT, source), os.path.join(stage, local))
        commands += install_commands(directory, name, local, mode)
        written.append((f'{directory}/{name}', local))
    if part == 'system':
        dumped = os.path.join(stage, 'build.prop.orig')
        subprocess.run([debugfs, '-R', f'dump /system/build.prop {dumped}', image],
                       capture_output=True, check=True)
        with open(dumped, errors='surrogateescape') as f:
            edited = edit_build_prop(f.read(), network_adb)
        with open(os.path.join(stage, 'build.prop'), 'w', errors='surrogateescape') as f:
            f.write(edited)
        commands += install_commands('/system', 'build.prop', 'build.prop', '0100644')
        written.append(('/system/build.prop', 'build.prop'))

    command_file = os.path.join(stage, f'{part}.cmds')
    with open(command_file, 'w') as f:
        f.write('\n'.join(commands) + '\n')
    subprocess.run([debugfs, '-w', '-f', command_file, image], cwd=stage, capture_output=True, check=True)
    check = subprocess.run([e2fsck, '-fy', image], capture_output=True, text=True, errors='replace')
    if check.returncode > 1:
        die(f'the filesystem check failed for {part}:\n{check.stdout}')

    for target, local in written:
        back = os.path.join(stage, 'verify.bin')
        subprocess.run([debugfs, '-R', f'dump {target} {back}', image], capture_output=True, check=True)
        if sha256(back) != sha256(os.path.join(stage, local)):
            die(f'{target} did not land in the image correctly.')
        os.remove(back)
    print(f'  {part}: built, {len(written)} files installed')


def make_delta(base, new, prefix):
    blocks = []
    digest = hashlib.sha256()
    with open(base, 'rb') as a, open(new, 'rb') as b, open(prefix + '.delta', 'wb') as out:
        index = 0
        while True:
            old_block = a.read(BLOCK)
            new_block = b.read(BLOCK)
            if not old_block and not new_block:
                break
            if len(old_block) != len(new_block):
                die('image sizes differ')
            digest.update(new_block)
            if old_block != new_block:
                out.write(new_block)
                blocks.append(index)
            index += 1
    with open(prefix + '.delta.list', 'w') as f:
        f.write('\n'.join(map(str, blocks)) + '\n')
    return digest.hexdigest()[:16], len(blocks), index * BLOCK


def write_partition(device, part, base, new, stage):
    prefix = os.path.join(stage, part)
    target, count, size = make_delta(base, new, prefix)
    before = sha256(base)[:16]
    device.push(prefix + '.delta', f'{DEVICE_TMP}/{part}.delta')
    device.push(prefix + '.delta.list', f'{DEVICE_TMP}/{part}.delta.list')
    output = device.shell(f'sh {DEVICE_TMP}/delta_apply.sh {part} {size} {before} {target}', check=False)
    if 'DELTA_OK' not in output or f'now={target}' not in output:
        die(f'writing {part} failed. Do not reboot yet, see docs/RECOVERY.md.\n{output}')
    device.shell(f'rm -f {DEVICE_TMP}/{part}.delta {DEVICE_TMP}/{part}.delta.list', check=False)
    print(f'  {part}: {count} blocks written and verified')


def save_layout(device, target):
    lines = []
    for name in PARTITIONS:
        lines.append(f'{name}: ' + device.shell(f'dmctl table {name}', check=False).replace('\n', ' '))
    with open(target, 'w') as f:
        f.write('\n'.join(lines) + '\n')


def main():
    parser = argparse.ArgumentParser(description='Build sevOS from your projector and install it.')
    parser.add_argument('--device', help='ADB serial or address, for example 192.168.1.50:3268')
    parser.add_argument('--work', default='sevos-work', help='folder for the backup and the build (about 3 GB)')
    parser.add_argument('--network-adb', action='store_true',
                        help='keep root ADB open on the network, anyone on your network can then control the device')
    parser.add_argument('--no-wipe', action='store_true', help='keep /data, leaves the vendor apps\' data in place')
    parser.add_argument('--force', action='store_true', help='skip the device compatibility checks')
    parser.add_argument('--from-dir', help='build from <dir>/<partition>.img without touching a device')
    parser.add_argument('--yes', action='store_true', help='do not ask for confirmation')
    args = parser.parse_args()

    tools = (find_tool('debugfs', 'DEBUGFS'), find_tool('e2fsck', 'E2FSCK'))
    stock_dir = os.path.abspath(os.path.join(args.work, 'stock'))
    build_dir = os.path.abspath(os.path.join(args.work, 'build'))
    os.makedirs(stock_dir, exist_ok=True)
    os.makedirs(build_dir, exist_ok=True)

    device = None
    if not args.from_dir:
        adb = find_tool('adb', 'ADB')
        if args.device and ':' in args.device:
            subprocess.run([adb, 'connect', args.device], stdin=subprocess.DEVNULL, capture_output=True)
        device = Device(adb, args.device)
        print('1. Checking the device')
        check_device(device, args.force)
        if not args.yes:
            print('\nThis replaces parts of the system and, unless --no-wipe is given, ERASES ALL APPS AND SETTINGS.')
            print(f'A copy of the original partitions stays in {stock_dir}. Keep it.')
            if input('Type INSTALL to continue: ').strip() != 'INSTALL':
                die('cancelled, nothing was changed.')
        print('2. Copying the partitions from the device')
        for part in PARTITIONS:
            device.pull_partition(part, os.path.join(stock_dir, f'{part}.img'))
        save_layout(device, os.path.join(stock_dir, 'layout.txt'))

    print('3. Building sevOS')
    for part in PARTITIONS:
        source_dir = os.path.abspath(args.from_dir) if args.from_dir else stock_dir
        source = os.path.join(source_dir, f'{part}.img')
        new = os.path.join(build_dir, f'{part}.img')
        shutil.copy(source, new)
        stage = tempfile.mkdtemp(prefix=f'{part}.', dir=build_dir)
        shutil.copy(os.path.join(ROOT, 'manifest', LABEL), os.path.join(stage, LABEL))
        build_partition(part, new, stage, tools, args.network_adb)
        shutil.rmtree(stage)
        if os.path.getsize(new) != os.path.getsize(source):
            die(f'{part} changed size')

    if args.from_dir:
        print(f'\nBuilt in {build_dir}. Nothing was written to any device.')
        return

    print('4. Writing the changed blocks')
    device.push(os.path.join(ROOT, 'installer', 'delta_apply.sh'), f'{DEVICE_TMP}/delta_apply.sh')
    stage = tempfile.mkdtemp(prefix='delta.', dir=build_dir)
    for part in PARTITIONS:
        write_partition(device, part, os.path.join(stock_dir, f'{part}.img'), os.path.join(build_dir, f'{part}.img'), stage)
    shutil.rmtree(stage)

    if args.no_wipe:
        print('5. Rebooting')
        device.shell('sync; reboot', check=False)
    else:
        print('5. Wiping user data and rebooting')
        script = os.path.join(build_dir, 'sevos_wipe.sh')
        with open(script, 'w') as f:
            f.write('mkdir -p /cache/recovery\nprintf "%s\\n" --wipe_data --wipe_cache > /cache/recovery/command\nsync\nreboot recovery\n')
        device.push(script, f'{DEVICE_TMP}/sevos_wipe.sh')
        device.shell(f'sh {DEVICE_TMP}/sevos_wipe.sh', check=False)
    print('\nDone. The first boot takes a few minutes. Connect to Wi-Fi from Settings on the home screen.')


if __name__ == '__main__':
    main()
