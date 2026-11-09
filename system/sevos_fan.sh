#!/system/bin/sh
# Slows the fan by cutting Lcd_power for a few milliseconds at 120 Hz.
# sys.sevos.fan is the power in percent, 10 to 100. It is not persistent, so every boot starts at full speed.
# A slower fan means less cooling. LED temperature cannot be measured on this board, and only the SoC is guarded below.
#
# This runs for the whole session and is never restarted: init delays a restart by up to 5 seconds,
# and the line would stay off for that long. init signals a change by writing to $FLAG.
# Inside the switching loop only shell builtins may run: anything that forks makes the fan surge.
F=/sys/class/leds/Lcd_power/brightness
FLAG=/dev/sevos_fan_changed
FIFO=/dev/sevos_fan_fifo

: > $FLAG
rm -f $FIFO
mkfifo $FIFO
exec 5<>$FIFO
rm -f $FIFO
exec 3>$F
typeset -Z6 o n

# Back to full speed if the SoC gets hot. The LED itself cannot be measured.
chrt -o 0 /system/bin/sh -c 'while sleep 10; do t=$(cat /sys/class/thermal/thermal_zone0/temp); if [ "$t" -ge 60000 ] && [ "$(getprop sys.sevos.fan)" != 100 ]; then setprop sys.sevos.fan 100; fi; done' &

while :; do
    # Clear first, so that a change arriving during the reads below is not lost.
    : > $FLAG
    p="$(getprop sys.sevos.fan)"
    case "$p" in
        ''|*[!0-9]*) p=100 ;;
    esac
    if [ "$(getprop debug.tracing.screen_state)" = 1 ]; then
        echo 0 >&3
        while [ ! -s $FLAG ]; do read -t 1 x <&5; done
        continue
    fi
    if [ "$p" -ge 100 ]; then
        echo 255 >&3
        while [ ! -s $FLAG ]; do read -t 1 x <&5; done
        continue
    fi
    [ "$p" -lt 10 ] && p=10

    # Period is 8333 us. Each phase runs about 700 us longer than the time asked for.
    off=$(( (100 - p) * 8333 / 100 ))
    o=$(( off - 700 ))
    n=$(( 8333 - off - 700 ))
    [ "$o" -lt 100 ] && o=100
    [ "$n" -lt 100 ] && n=100

    while [ ! -s $FLAG ]; do
        echo 0 >&3
        read -t 0.$o x <&5
        echo 255 >&3
        read -t 0.$n x <&5
    done
    echo 255 >&3
done
