#!/system/bin/sh
# Root actions requested by the launcher through sys.sevos.cmd.
cmd="$(getprop sys.sevos.cmd)"
[ -z "$cmd" ] && exit 0
setprop sys.sevos.cmd ""

case "$cmd" in
clean)
    # Stops user apps and empties the caches of the processes that remain.
    # The file page cache is kept on purpose: it is what makes the next launch fast.
    for pkg in $(pm list packages -3 | cut -d: -f2); do
        am force-stop "$pkg"
    done
    am kill-all
    echo 1 > /proc/sys/vm/compact_memory
    ;;
sleep)
    input keyevent 223
    ;;
color)
    sat="$(getprop persist.sys.sevos.saturation)"
    [ -n "$sat" ] && service call SurfaceFlinger 1022 f "$sat" >/dev/null 2>&1
    ;;
esac
