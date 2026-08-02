#!/system/bin/sh
# Docking Enhancer — on-device e2e suite.
#
# Exercises the shipped daemon against synthetic controllers, so every run is identical and no
# physical pad has to stay awake. Run it from the host with:
#     adb shell sh /data/local/tmp/e2e.sh
#
# Scope note: this drives the DAEMON directly. The app's supervisor flows (dock gating, auto-start,
# settings) are covered separately in the skill's manual checklist, because they need the real
# no-root PServer path rather than a shell-spawned process.

MIRROR=/data/local/tmp/input_mirror
VPAD=/data/local/tmp/vgamepad
TMP=/data/local/tmp/e2e
STATE=$TMP/hidden.state
HEART=$TMP/heartbeat
LOG=$TMP/mirror.log

PASS=0
FAIL=0

ok()  { echo "  PASS  $1"; PASS=$((PASS + 1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }

expect_exists()    { if [ -e "$1" ]; then ok "$2"; else bad "$2  [falta $1]"; fi; }
expect_missing()   { if [ ! -e "$1" ]; then ok "$2"; else bad "$2  [todavia existe $1]"; fi; }
expect_contains()  { if grep -q "$2" "$1" 2>/dev/null; then ok "$3"; else bad "$3"; fi; }

wait_for_file() {
    i=0
    while [ ! -s "$1" ] && [ "$i" -lt 60 ]; do sleep 0.1; i=$((i + 1)); done
}

wait_for_gone() {
    # $1 = path, $2 = max tenths of a second
    i=0
    while [ -e "$1" ] && [ "$i" -lt "$2" ]; do sleep 0.1; i=$((i + 1)); done
}

mirror_pid() { pidof input_mirror 2>/dev/null; }

stop_mirror() {
    P=$(mirror_pid)
    [ -n "$P" ] && su -c "kill -KILL $P" 2>/dev/null
    i=0
    while [ -n "$(mirror_pid)" ] && [ "$i" -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
}

# Start the daemon as root, detached. Root is test scaffolding only — the product itself reaches the
# same binary through PServerBinder with no root at all.
start_mirror() {
    su -c "$MIRROR $* > $LOG 2>&1 &"
    i=0
    while [ -z "$(mirror_pid)" ] && [ "$i" -lt 30 ]; do sleep 0.1; i=$((i + 1)); done
}

# --- setup ---------------------------------------------------------------------------------------

echo "=== setup ==="

# The app's supervisor would fight the suite for the controllers, so park it first.
am force-stop com.odininputmirror 2>/dev/null
stop_mirror

rm -rf $TMP
mkdir -p $TMP

# Test the binary the app actually ships, not a rebuild that might drift from it.
if ! su -c "cp /data/data/com.odininputmirror/files/bin/input_mirror $MIRROR && chmod 755 $MIRROR" 2>/dev/null; then
    echo "  ABORT: no pude copiar el binario del daemon (esta instalada la app?)"
    exit 1
fi
echo "  daemon: $(su -c "$MIRROR" 2>&1 | head -n 1 | cut -c1-40)..."

SRCFIFO=$TMP/src.fifo
DSTFIFO=$TMP/dst.fifo
mkfifo $SRCFIFO $DSTFIFO

# Source keeps the Odin quirk vendor (0x2020): hide_node refuses anything else. The target takes the
# Xbox product id so the daemon's Nintendo face-button swap stays off and forwarding is 1:1.
$VPAD --name "E2E Source Pad" --vendor 0x2020 --product 0x0111 --path-file $TMP/src.path < $SRCFIFO &
exec 3> $SRCFIFO
$VPAD --name "E2E Target Pad" --vendor 0x2020 --product 0x0112 --path-file $TMP/dst.path < $DSTFIFO &
exec 4> $DSTFIFO

wait_for_file $TMP/src.path
wait_for_file $TMP/dst.path
SRC=$(cat $TMP/src.path)
DST=$(cat $TMP/dst.path)

if [ -z "$SRC" ] || [ -z "$DST" ]; then
    echo "  ABORT: no se crearon los pads sinteticos"
    exit 1
fi
echo "  source=$SRC  target=$DST"

# --- T1: forwarding fidelity ---------------------------------------------------------------------

echo ""
echo "=== T1: el daemon reenvia eventos al target ==="

start_mirror "$SRC" "$DST" --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco"
else
    ok "el daemon arranco (pid $(mirror_pid))"

    getevent -lt "$DST" > $TMP/fwd.txt 2>&1 &
    CAP=$!
    sleep 0.4

    echo "key 304 1" >&3; echo "syn" >&3
    echo "key 304 0" >&3; echo "syn" >&3
    echo "burst 40" >&3
    sleep 1.2
    kill $CAP 2>/dev/null

    # getevent labels 0x130 as BTN_GAMEPAD; BTN_SOUTH/BTN_A are aliases of the same code.
    expect_contains $TMP/fwd.txt "BTN_GAMEPAD" "el boton llega al target"
    expect_contains $TMP/fwd.txt "SYN_REPORT" "las tramas cierran con SYN_REPORT"

    ABS=$(grep -c "ABS_X" $TMP/fwd.txt 2>/dev/null)
    [ -z "$ABS" ] && ABS=0
    if [ "$ABS" -ge 30 ]; then
        ok "la rafaga llega completa (ABS_X x$ABS de 40)"
    else
        bad "la rafaga se perdio (ABS_X x$ABS, esperaba >=30)"
    fi
fi
stop_mirror

# --- T2: hide + restore --------------------------------------------------------------------------

echo ""
echo "=== T2: oculta el pad externo y lo devuelve al salir ==="

start_mirror "$SRC" "$DST" --hide-node "$SRC" --hidden-state-file $STATE --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para ocultar"
else
    sleep 0.5
    expect_missing "$SRC" "el nodo del source desaparece de /dev/input"
    expect_exists  "$STATE" "queda registro de lo ocultado"

    # SIGTERM is the product's own stop signal; it must take the clean path that restores.
    su -c "kill -TERM $(mirror_pid)" 2>/dev/null
    stop_mirror
    sleep 0.5

    expect_exists  "$SRC" "el nodo vuelve tras una parada limpia"
    expect_missing "$STATE" "el registro se borra cuando restauro todo"
fi

# --- T3: orphan heal -----------------------------------------------------------------------------

echo ""
echo "=== T3: un daemon matado a lo bruto se puede sanar ==="

start_mirror "$SRC" "$DST" --hide-node "$SRC" --hidden-state-file $STATE --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de huerfano"
else
    sleep 0.5
    su -c "kill -KILL $(mirror_pid)" 2>/dev/null
    sleep 0.5

    expect_missing "$SRC" "SIGKILL deja el nodo oculto (sin cleanup)"
    expect_exists  "$STATE" "el registro sobrevive para poder sanar"

    su -c "$MIRROR --heal --hidden-state-file $STATE" 2>/dev/null
    sleep 0.3

    expect_exists  "$SRC" "--heal devuelve el nodo huerfano"
    expect_missing "$STATE" "--heal limpia el registro"
fi

# --- T4: owner watchdog --------------------------------------------------------------------------

echo ""
echo "=== T4: el daemon no sobrevive a su app ==="

start_mirror "$SRC" "$DST" --hide-node "$SRC" --hidden-state-file $STATE --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de watchdog"
else
    sleep 0.5
    expect_missing "$SRC" "el nodo esta oculto antes de la prueba"

    # Deleting the heartbeat is how the app signals "you have no owner": uninstalled, data cleared,
    # or a duplicate launch it abandoned.
    su -c "rm -f $HEART"
    wait_for_gone "/proc/$(mirror_pid)" 80

    if [ -z "$(mirror_pid)" ]; then
        ok "el daemon se apaga solo al perder su heartbeat"
    else
        bad "el daemon sigue vivo sin dueno"
        stop_mirror
    fi
    sleep 0.3
    expect_exists "$SRC" "y aun asi devuelve el nodo al salir"
fi

# --- T5: refuses to hide a node that is not the expected device ------------------------------------

echo ""
echo "=== T5: se niega a ocultar un dispositivo ajeno ==="

# Event numbers get recycled across reconnects, so the path the app resolved may point at something
# else by the time the daemon acts on it. Unlinking blindly there would take out an unrelated
# device; the daemon must check identity (EVIOCGID) and refuse.
#
# The decoy is --plain (a non-controller) rather than a foreign-vendor pad, because this handheld
# never lets a foreign-vendor CONTROLLER keep its node: the firmware republishes it as a 0x2020 twin
# and deletes the original, so there would be nothing left to test the refusal against.
FOREIGNFIFO=$TMP/foreign.fifo
mkfifo $FOREIGNFIFO
$VPAD --plain --name "E2E Decoy Device" --vendor 0x1234 --product 0x5678 --path-file $TMP/foreign.path < $FOREIGNFIFO &
exec 5> $FOREIGNFIFO
wait_for_file $TMP/foreign.path
FOREIGN=$(cat $TMP/foreign.path)

if [ -z "$FOREIGN" ] || [ ! -e "$FOREIGN" ]; then
    bad "se creo el senuelo y sobrevivio (sin el, el test no prueba nada)"
else
    ok "el senuelo existe antes de la prueba"
    start_mirror "$SRC" "$DST" --hide-node "$FOREIGN" --hidden-state-file $STATE --heartbeat-file $HEART
    sleep 0.6
    expect_exists "$FOREIGN" "el daemon NO desvincula un dispositivo ajeno"
    expect_contains $LOG "failed identity check" "y lo rechaza por identidad, no por accidente"
    stop_mirror
fi
exec 5>&-

# --- T6: hot reload ------------------------------------------------------------------------------

echo ""
echo "=== T6: cambiar un ajuste no reinicia el daemon ==="

CFG=$TMP/config.json
FIFO=$TMP/ctl
rm -f $CFG $FIFO
mkfifo $FIFO 2>/dev/null

generation() { sed -n 's/^generation //p' $HEART 2>/dev/null; }
write_config() {
    printf '{ "generation": %s, "home_as_back": %s, "combo_hold_kill_app": false, "virtual_mouse": false }\n' \
        "$1" "$2" > $CFG.tmp
    mv $CFG.tmp $CFG
}
# El daemon mantiene el fifo abierto en O_RDWR, asi que siempre hay lector; el timeout esta para que
# un daemon muerto a mitad del test falle en vez de colgar la suite entera.
poke() { timeout 2 sh -c "echo reload > $FIFO"; }

write_config 1 false
start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --hidden-state-file $STATE --heartbeat-file $HEART
PID_BEFORE=$(mirror_pid)
if [ -z "$PID_BEFORE" ]; then
    bad "el daemon arranco para el test de reload"
else
    sleep 1.2
    G=$(generation)
    if [ "$G" = "1" ]; then ok "adopta la config del arranque"; else bad "no publico la generacion inicial [$G]"; fi

    write_config 2 true
    poke
    sleep 1.5
    G=$(generation)
    if [ "$G" = "2" ]; then ok "adopta una config nueva sin reiniciar"; else bad "no adopto la generacion 2 [$G]"; fi

    echo '{ roto' > $CFG.tmp && mv $CFG.tmp $CFG
    poke
    sleep 1.5
    G=$(generation)
    if [ "$G" = "2" ]; then ok "rechaza una config rota y conserva la que corre"; else bad "adopto una config invalida [$G]"; fi
    expect_contains $LOG "keeping the running config" "y lo dice en el log en vez de fallar en silencio"

    # La asercion que le da sentido a todo el mecanismo: mismo proceso de punta a punta, o sea que el
    # grab exclusivo nunca se solto y el pad nunca se hizo visible.
    if [ "$(mirror_pid)" = "$PID_BEFORE" ]; then
        ok "el pid no cambio en ningun momento (no hubo restart)"
    else
        bad "el daemon se reinicio ($PID_BEFORE -> $(mirror_pid))"
    fi
    stop_mirror
fi
rm -f $CFG $FIFO

# --- teardown ------------------------------------------------------------------------------------

echo ""
echo "=== teardown ==="
stop_mirror
exec 3>&-
exec 4>&-
sleep 0.5
su -c "rm -rf $TMP $MIRROR" 2>/dev/null
echo "  limpio"

echo ""
echo "=================================="
echo "  PASS: $PASS    FAIL: $FAIL"
echo "=================================="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
