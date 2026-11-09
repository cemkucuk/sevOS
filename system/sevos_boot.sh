#!/system/bin/sh
# Runs once per boot, after sys.boot_completed=1.

# Instant standby: never enter kernel suspend, so waking up takes no resume time.
echo sevos > /sys/power/wake_lock

settings put global device_provisioned 1
settings put secure user_setup_complete 1
settings put global window_animation_scale 0.5
settings put global transition_animation_scale 0.5
settings put global animator_duration_scale 0.5

sat="$(getprop persist.sys.sevos.saturation)"
if [ -n "$sat" ]; then
    service call SurfaceFlinger 1022 f "$sat" >/dev/null 2>&1
fi
