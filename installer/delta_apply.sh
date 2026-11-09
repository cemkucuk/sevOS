#!/system/bin/sh
# usage: delta_apply.sh <partition> <size> <base16> <target16>
P=/dev/block/mapper/$1
T=/data/local/tmp
[ "$(blockdev --getsize64 $P)" = "$2" ] || { echo "BAD_SIZE $1"; exit 1; }
before=$(sha256sum $P | cut -c1-16)
[ "$before" = "$3" ] || { echo "BAD_BASE $1 $before"; exit 1; }
blockdev --setrw $P || { echo "SETRW_FAIL $1"; exit 1; }
i=0
for b in $(cat $T/$1.delta.list); do
  dd if=$T/$1.delta of=$P bs=4096 skip=$i seek=$b count=1 conv=notrunc 2>/dev/null || { echo "WRITE_FAIL $1 block=$b index=$i"; exit 2; }
  i=$((i+1))
done
sync
blockdev --flushbufs $P
blockdev --setro $P
echo "$1 written=$i"
echo "$1 now=$(sha256sum $P | cut -c1-16) expected=$4"
echo "DELTA_OK $1"
