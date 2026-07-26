#!/system/bin/sh
# Measures what the mirror adds to a button press.
#
# Runs the shipped daemon between two synthetic pads and times a press round trip — see
# run_latency() in vgamepad.c for why the timing lives inside the injecting process.
#
#     adb shell sh /data/local/tmp/latency.sh [samples]

SAMPLES=${1:-300}
MIRROR=/data/local/tmp/input_mirror
VPAD=/data/local/tmp/vgamepad
TMP=/data/local/tmp/lat

rm -rf $TMP; mkdir -p $TMP
am force-stop com.odininputmirror 2>/dev/null
P=$(pidof input_mirror); [ -n "$P" ] && su -c "kill -KILL $P" 2>/dev/null
sleep 0.5

if ! su -c "cp /data/data/com.odininputmirror/files/bin/input_mirror $MIRROR && chmod 755 $MIRROR" 2>/dev/null; then
    echo "ABORT: no pude copiar el daemon (esta instalada la app?)"
    exit 1
fi

mkfifo $TMP/s.fifo $TMP/d.fifo
# Target takes the Xbox product id so the Nintendo face-button swap stays off and BTN_SOUTH
# arrives as itself — the measurement watches for exactly that code.
$VPAD --name "Lat Source" --vendor 0x2020 --product 0x0111 --path-file $TMP/s.path < $TMP/s.fifo &
exec 3> $TMP/s.fifo
$VPAD --name "Lat Target" --vendor 0x2020 --product 0x0112 --path-file $TMP/d.path < $TMP/d.fifo &
exec 4> $TMP/d.fifo

i=0
while { [ ! -s $TMP/s.path ] || [ ! -s $TMP/d.path ]; } && [ $i -lt 60 ]; do sleep 0.1; i=$((i+1)); done
SRC=$(cat $TMP/s.path); DST=$(cat $TMP/d.path)
if [ -z "$SRC" ] || [ -z "$DST" ]; then echo "ABORT: no se crearon los pads"; exit 1; fi
echo "source=$SRC  target=$DST  samples=$SAMPLES"

# Control first, while the source is still ungrabbed: write to the pad and read it back off its own
# node, with no daemon in between. That is the cost of uinput, the input core and this process's own
# wake-up — the floor any measurement here carries. Subtract it from the mirrored figure and what is
# left is the daemon's actual contribution.
echo ""
echo "=== control: mismo ida y vuelta SIN el mirror ==="
echo "measure $SRC $SAMPLES" >&3
sleep $(( SAMPLES / 100 + 6 ))

# No --hide-node here: hiding is irrelevant to forwarding cost and would only add a variable.
su -c "$MIRROR $SRC $DST --heartbeat-file $TMP/hb > $TMP/log 2>&1 &"
i=0
while [ -z "$(pidof input_mirror)" ] && [ $i -lt 30 ]; do sleep 0.1; i=$((i+1)); done
if [ -z "$(pidof input_mirror)" ]; then echo "ABORT: el daemon no arranco"; cat $TMP/log; exit 1; fi
echo "daemon pid=$(pidof input_mirror)"
sleep 0.5

echo ""
echo "=== latencia agregada por el mirror (ida y vuelta) ==="
echo "measure $DST $SAMPLES" >&3
sleep $(( SAMPLES / 100 + 6 ))

P=$(pidof input_mirror); [ -n "$P" ] && su -c "kill -TERM $P" 2>/dev/null
sleep 0.5
exec 3>&-
exec 4>&-
sleep 0.3
su -c "rm -rf $TMP $MIRROR" 2>/dev/null
