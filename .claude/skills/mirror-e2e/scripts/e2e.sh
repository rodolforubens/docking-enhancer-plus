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
    # TMP comes first so a test can replace a system command with a recorder. Until such a file is
    # created the normal Android tools are found in /system/bin as usual.
    su -c "PATH=$TMP:/system/bin:/system/xbin $MIRROR $* > $LOG 2>&1 &"
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
SRC_PAD_PID=$!
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
    printf '{ "generation": %s, "home_single_action": %s, "home_double_action": 0, "home_hold_action": 0, "select_start_hold_action": 0, "select_r3_hold_action": 0 }\n' \
        "$1" "$2" > $CFG.tmp
    mv $CFG.tmp $CFG
}
# El daemon mantiene el fifo abierto en O_RDWR, asi que siempre hay lector; el timeout esta para que
# un daemon muerto a mitad del test falle en vez de colgar la suite entera.
poke() { poke_cmd reload; }
# Cualquier comando del canal de control. El daemon mantiene el fifo abierto en O_RDWR, asi que
# siempre hay lector; el timeout esta para que un daemon muerto falle en vez de colgar la suite.
poke_cmd() { timeout 2 sh -c "echo '$1' > $FIFO"; }
# Una pulsacion completa en el pad sintetico de origen (fd 3 es su stdin).
press_source() { echo "key $1 1" >&3; echo "syn" >&3; echo "key $1 0" >&3; echo "syn" >&3; }
# Un eje del pad de origen a un valor concreto. Los ejes van de -32768 a 32767 con reposo en 0.
move_source() { echo "abs $1 $2" >&3; echo "syn" >&3; }

write_config 1 0
start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --hidden-state-file $STATE --heartbeat-file $HEART
PID_BEFORE=$(mirror_pid)
if [ -z "$PID_BEFORE" ]; then
    bad "el daemon arranco para el test de reload"
else
    sleep 1.2
    G=$(generation)
    if [ "$G" = "1" ]; then ok "adopta la config del arranque"; else bad "no publico la generacion inicial [$G]"; fi

    write_config 2 1
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

# --- T7: capture mode ----------------------------------------------------------------------------

echo ""
echo "=== T7: la captura reporta y no reenvia ==="

CAPLOG=$TMP/capture.log
rm -f $CAPLOG $CFG $FIFO
mkfifo $FIFO 2>/dev/null
write_config 1 0

start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --capture-file $CAPLOG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de captura"
else
    poke_cmd "capture button"
    sleep 0.5

    # Con la captura abierta, un boton tiene que aparecer en el log y NO llegar al target: apretar A
    # para mapearla no puede disparar A en lo que este en pantalla.
    timeout 3 getevent -lc 4 "$DST" > $TMP/t7.out 2>&1 &
    T7GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T7GE 2>/dev/null

    expect_contains $CAPLOG "button 304" "reporta el codigo apretado"
    if [ -s $TMP/t7.out ]; then
        bad "reenvio al target mientras capturaba"
    else
        ok "no reenvia nada mientras captura"
    fi

    # El latch: una segunda pulsacion no agrega otra linea hasta que se pida el paso siguiente.
    press_source 305
    sleep 1
    if [ "$(wc -l < $CAPLOG)" = "1" ]; then
        ok "el latch retiene hasta el proximo paso"
    else
        bad "el latch no retuvo ($(wc -l < $CAPLOG) lineas)"
    fi

    poke_cmd "capture off"
    sleep 0.5
    rm -f $TMP/t7.out
    timeout 3 getevent -lc 4 "$DST" > $TMP/t7.out 2>&1 &
    T7GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T7GE 2>/dev/null
    expect_contains $TMP/t7.out "BTN_GAMEPAD" "vuelve a reenviar al cerrar la captura"

    stop_mirror
fi
rm -f $CAPLOG $TMP/t7.out

# --- T8: el mapeo remapea ------------------------------------------------------------------------

echo ""
echo "=== T8: un mapeo en la config cambia el codigo reenviado ==="

# 304 (BTN_SOUTH) -> 307 (BTN_NORTH); 305 queda sin mapear y tiene que pasar intacto.
cat > $CFG.tmp <<'EOF'
{
  "generation": 1,
  "home_single_action": 0,
  "home_double_action": 0,
  "home_hold_action": 0,
  "select_start_hold_action": 0,
  "select_r3_hold_action": 0,
  "mapping": { "bindings": [[0, 304, 0, 0, 307, 0]] }
}
EOF
mv $CFG.tmp $CFG

start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de mapeo"
else
    timeout 4 getevent -lc 8 "$DST" > $TMP/t8.out 2>&1 &
    T8GE=$!
    sleep 0.5
    press_source 304
    press_source 305
    sleep 2
    kill $T8GE 2>/dev/null

    expect_contains $TMP/t8.out "BTN_NORTH" "el codigo mapeado llega remapeado"
    expect_contains $TMP/t8.out "BTN_EAST" "el codigo sin mapear pasa intacto"
    # getevent nombra 0x130 como BTN_GAMEPAD; si aparece, el mapeo no se aplico.
    if grep -q "BTN_GAMEPAD" $TMP/t8.out 2>/dev/null; then
        bad "llego el codigo ORIGINAL: el mapeo no se aplico"
    else
        ok "el codigo original no llega"
    fi

    stop_mirror
fi
rm -f $TMP/t8.out $CFG $FIFO

# --- T9: un eje llena un slot de boton --------------------------------------------------------------

echo ""
echo "=== T9: media travesia de un eje llega como boton ==="

# Lo que la division vieja (una tabla de botones, otra de ejes, sin puente) no podia expresar: el
# gatillo de un pad que lo reporta como eje ocupando el slot de un boton.
# [kind=2 (media travesia), ABS_Z, +1] -> [kind=0 (boton), BTN_NORTH]
cat > $CFG.tmp <<'EOF'
{
  "generation": 1,
  "home_single_action": 0,
  "home_double_action": 0,
  "home_hold_action": 0,
  "select_start_hold_action": 0,
  "select_r3_hold_action": 0,
  "mapping": { "bindings": [[2, 2, 1, 0, 307, 0]] }
}
EOF
mv $CFG.tmp $CFG

start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de eje-a-boton"
else
    # Primero por debajo del umbral: un gatillo apenas rozado no puede contar como apretado, o el
    # boton castanetea con el ruido del sensor.
    timeout 4 getevent -lc 6 "$DST" > $TMP/t9a.out 2>&1 &
    T9GE=$!
    sleep 0.5
    move_source 2 5000
    sleep 1.5
    kill $T9GE 2>/dev/null
    if grep -q "BTN_NORTH" $TMP/t9a.out 2>/dev/null; then
        bad "un roce del eje ya disparo el boton"
    else
        ok "por debajo del umbral no dispara"
    fi

    # Y ahora hasta el fondo.
    timeout 4 getevent -lc 8 "$DST" > $TMP/t9b.out 2>&1 &
    T9GE=$!
    sleep 0.5
    move_source 2 30000
    sleep 0.6
    move_source 2 0
    sleep 1.5
    kill $T9GE 2>/dev/null

    expect_contains $TMP/t9b.out "BTN_NORTH" "pasado el umbral llega como boton"
    # El evento de eje se consume: lo que el target ve es el boton, no las dos cosas.
    if grep -q "ABS_Z" $TMP/t9b.out 2>/dev/null; then
        bad "el eje original tambien llego al target"
    else
        ok "el eje original no llega"
    fi
    # Bajada y subida: sin la de vuelta el boton queda trabado apretado para siempre.
    if [ "$(grep -c "BTN_NORTH" $TMP/t9b.out 2>/dev/null)" = "2" ]; then
        ok "suelta el boton al volver el eje a reposo"
    else
        bad "no emitio el par apretar/soltar ($(grep -c "BTN_NORTH" $TMP/t9b.out 2>/dev/null) eventos)"
    fi

    stop_mirror
fi
rm -f $TMP/t9a.out $TMP/t9b.out $CFG

# --- T10: un boton llena una direccion de stick -----------------------------------------------------

echo ""
echo "=== T10: un boton llega como deflexion completa de un eje ==="

# El cruce inverso: [kind=0 (boton), BTN_SOUTH] -> [kind=2 (media travesia), ABS_RX, +1].
cat > $CFG.tmp <<'EOF'
{
  "generation": 1,
  "home_single_action": 0,
  "home_double_action": 0,
  "home_hold_action": 0,
  "select_start_hold_action": 0,
  "select_r3_hold_action": 0,
  "mapping": { "bindings": [[0, 304, 0, 2, 3, 1]] }
}
EOF
mv $CFG.tmp $CFG

start_mirror "$SRC" "$DST" --config-file $CFG --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de boton-a-eje"
else
    timeout 4 getevent -lc 8 "$DST" > $TMP/t10.out 2>&1 &
    T10GE=$!
    sleep 0.5
    press_source 304
    sleep 1.5
    kill $T10GE 2>/dev/null

    # Una fuente digital no tiene medias tintas: da el tope del recorrido, 32767 = 0x7fff.
    expect_contains $TMP/t10.out "ABS_RX" "el boton llega como eje"
    expect_contains $TMP/t10.out "00007fff" "y con la deflexion al tope"
    # Y vuelve a reposo al soltar, o el stick queda clavado contra el borde.
    expect_contains $TMP/t10.out "00000000" "vuelve a reposo al soltar"
    if grep -q "BTN_GAMEPAD" $TMP/t10.out 2>/dev/null; then
        bad "el boton original tambien llego al target"
    else
        ok "el boton original no llega"
    fi

    stop_mirror
fi
rm -f $TMP/t10.out $CFG $FIFO

# --- T11: la captura no puede dejar el pad mudo -----------------------------------------------------

echo ""
echo "=== T11: una captura abandonada expira sola ==="

# Mientras hay un paso abierto el daemon no reenvia NADA. Si la app se cae ahi, el pad queda mudo y
# no hay forma de revivirlo desde el pad mismo — el timeout es la unica salida. Tarda sus 30s reales
# porque es lo que se esta probando; no hay forma honesta de acelerarlo.
rm -f $CFG $FIFO
mkfifo $FIFO 2>/dev/null
write_config 1 0

start_mirror "$SRC" "$DST" --config-file $CFG --control-fifo $FIFO --capture-file $TMP/t11.cap --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para el test de expiracion"
else
    poke_cmd "capture button"
    sleep 0.5

    # Confirmar primero que efectivamente esta mudo, o el test pasaria aunque la captura ni se abrio.
    timeout 3 getevent -lc 4 "$DST" > $TMP/t11a.out 2>&1 &
    T11GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T11GE 2>/dev/null
    if [ -s $TMP/t11a.out ]; then
        bad "la captura ni siquiera silencio el pad"
    else
        ok "con la captura abierta el pad esta mudo"
    fi

    echo "  (esperando los 30s del timeout...)"
    sleep 31

    timeout 4 getevent -lc 4 "$DST" > $TMP/t11b.out 2>&1 &
    T11GE=$!
    sleep 0.4
    press_source 304
    sleep 1.5
    kill $T11GE 2>/dev/null
    expect_contains $TMP/t11b.out "BTN_GAMEPAD" "el pad revive solo sin que nadie cierre la captura"

    stop_mirror
fi
rm -f $TMP/t11.cap $TMP/t11a.out $TMP/t11b.out $CFG $FIFO

# --- T12: virtual mouse ----------------------------------------------------------------------------
#
# The one feature whose failure is silent: the pointer lives on its own uinput device, so a mistake
# here does not break forwarding, it just means Select+R3 does nothing — or worse, leaves a pointer
# behind that nothing will ever destroy. Asserts on the device's existence in /proc/bus/input/devices
# rather than on a log line, because that is what Android itself reacts to.

echo ""
echo "=== T12: el raton virtual aparece y se va con el combo ==="

# The pointer's own node, by the name mouse.c gives it. The handlers line reads
# "H: Handlers=event7 mouse2", so the prefix has to come off before the fields mean anything.
mouse_node() {
    awk '/Name="Docking Enhancer Mouse"/{f=1}
         f && /Handlers=/ {
             sub(/.*Handlers=/, "")
             for (i = 1; i <= NF; i++) if ($i ~ /^event/) { print "/dev/input/" $i; exit }
         }' /proc/bus/input/devices 2>/dev/null
}

# Select+R3 has to be HELD past the toggle threshold (500ms); the daemon completes it on a poll
# timeout, so nothing new needs to arrive for it to fire.
hold_mouse_combo() {
    echo "key 314 1" >&3; echo "syn" >&3
    echo "key 318 1" >&3; echo "syn" >&3
    sleep 0.9
    echo "key 318 0" >&3; echo "syn" >&3
    echo "key 314 0" >&3; echo "syn" >&3
    echo "syn" >&3
    sleep 0.4
}

start_mirror "$SRC" "$DST" --select-r3-hold-action 5 --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco en modo raton"
else
    if [ -z "$(mouse_node)" ]; then
        ok "sin el combo no hay puntero"
    else
        bad "sin el combo no hay puntero  [ya existe $(mouse_node)]"
    fi

    hold_mouse_combo
    MOUSE=$(mouse_node)

    if [ -n "$MOUSE" ]; then
        ok "el combo crea el puntero ($MOUSE)"

        getevent -lt "$MOUSE" > $TMP/t12.mouse 2>&1 &
        T12M=$!
        getevent -lt "$DST"   > $TMP/t12.dst   2>&1 &
        T12D=$!
        sleep 0.4

        # Stick deflection becomes cursor motion on the frame cadence, not per event.
        echo "abs 0 30000" >&3; echo "syn" >&3
        sleep 0.3
        echo "abs 0 -30000" >&3; echo "syn" >&3
        sleep 0.3
        echo "abs 0 0" >&3; echo "syn" >&3
        # A and B become the two clicks.
        echo "key 304 1" >&3; echo "syn" >&3
        sleep 0.2
        echo "key 304 0" >&3; echo "syn" >&3
        echo "key 305 1" >&3; echo "syn" >&3
        sleep 0.2
        echo "key 305 0" >&3; echo "syn" >&3
        sleep 0.5
        kill $T12M $T12D 2>/dev/null

        expect_contains $TMP/t12.mouse "REL_X" "el stick mueve el cursor"
        # getevent prints 0x110 as BTN_MOUSE, never BTN_LEFT — same code, different alias, exactly
        # like BTN_GAMEPAD for 0x130. Asserting on BTN_LEFT reads as a broken click that works fine.
        expect_contains $TMP/t12.mouse "BTN_MOUSE" "A hace click izquierdo"
        expect_contains $TMP/t12.mouse "BTN_RIGHT" "B hace click derecho"
        # The whole point of mouse mode: the game underneath stops receiving the pad.
        if grep -q "BTN_GAMEPAD" $TMP/t12.dst 2>/dev/null; then
            bad "el pad sigue llegando al target mientras el raton esta activo"
        else
            ok "el pad deja de llegar al target mientras el raton esta activo"
        fi

        hold_mouse_combo
        if [ -z "$(mouse_node)" ]; then
            ok "el combo se lleva el puntero"
        else
            bad "el combo se lleva el puntero  [sigue ahi $(mouse_node)]"
        fi

        # Forwarding has to come back, or the pad is left mute.
        getevent -lt "$DST" > $TMP/t12.back 2>&1 &
        T12B=$!
        sleep 0.4
        echo "key 304 1" >&3; echo "syn" >&3
        echo "key 304 0" >&3; echo "syn" >&3
        sleep 0.5
        kill $T12B 2>/dev/null
        expect_contains $TMP/t12.back "BTN_GAMEPAD" "el pad vuelve a llegar al target al salir"
    else
        bad "el combo crea el puntero"
    fi

    stop_mirror
    # Nothing may outlive the daemon: a stranded pointer would sit in the device list forever.
    if [ -z "$(mouse_node)" ]; then
        ok "no queda ningun puntero tras cerrar el daemon"
    else
        bad "quedo un puntero huerfano  [$(mouse_node)]"
    fi
fi
rm -f $TMP/t12.mouse $TMP/t12.dst $TMP/t12.back

# --- T13: Home tap / hold / double-tap -------------------------------------------------------------

echo ""
echo "=== T13: Home corto va al inicio; doble vuelve; sostenido abre Recientes ==="

HOME_ACTIONS=$TMP/home-actions
cat > $TMP/input <<'EOF'
#!/system/bin/sh
echo "$*" >> /data/local/tmp/e2e/home-actions
EOF
chmod 755 $TMP/input
rm -f $HOME_ACTIONS

start_mirror "$SRC" "$DST" --home-single-action 1 --home-double-action 2 --home-hold-action 3 --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para probar Home"
else
    # BTN_MODE is 316 on the synthetic controller. A quick press owes Home only after the
    # double-tap window closes.
    echo "key 316 1" >&3; echo "syn" >&3
    sleep 0.2
    if [ -e $HOME_ACTIONS ]; then
        bad "Home corto no actua antes de soltar"
    else
        ok "Home corto no actua antes de soltar"
    fi
    echo "key 316 0" >&3; echo "syn" >&3
    sleep 0.2
    if [ -e $HOME_ACTIONS ]; then
        bad "Home corto espera la ventana de doble toque"
    else
        ok "Home corto espera la ventana de doble toque"
    fi
    sleep 0.4
    expect_contains $HOME_ACTIONS "keyevent 3" "Home corto envia Home"
    if grep -q "keyevent 4" $HOME_ACTIONS 2>/dev/null; then
        bad "Home corto no envia Back"
    else
        ok "Home corto no envia Back"
    fi

    # A hold crosses 500ms and must open Recents before release.
    rm -f $HOME_ACTIONS
    echo "key 316 1" >&3; echo "syn" >&3
    sleep 0.8
    expect_contains $HOME_ACTIONS "keyevent 187" "Home sostenido abre Recientes durante la pulsacion"
    echo "key 316 0" >&3; echo "syn" >&3
    sleep 0.4
    if grep -q "keyevent 4" $HOME_ACTIONS 2>/dev/null; then
        bad "Home sostenido no envia Back al soltar"
    else
        ok "Home sostenido no envia Back al soltar"
    fi

    rm -f $HOME_ACTIONS
    echo "key 316 1" >&3; echo "syn" >&3
    echo "key 316 0" >&3; echo "syn" >&3
    sleep 0.12
    echo "key 316 1" >&3; echo "syn" >&3
    echo "key 316 0" >&3; echo "syn" >&3
    sleep 0.6

    expect_contains $HOME_ACTIONS "keyevent 4" "doble Home envia Back"
    if grep -qE "keyevent (3|187)" $HOME_ACTIONS 2>/dev/null; then
        bad "doble Home no envia Home ni Recientes"
    else
        ok "doble Home no envia Home ni Recientes"
    fi

    stop_mirror
fi

# Sleep is another assignable action, not special Home handling. With no double-tap assignment the
# single press fires as soon as Home is released; the fake input command keeps the device awake.
rm -f $HOME_ACTIONS
start_mirror "$SRC" "$DST" --home-single-action 6 --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para probar Sleep"
else
    echo "key 316 1" >&3; echo "syn" >&3
    echo "key 316 0" >&3; echo "syn" >&3
    sleep 0.3
    expect_contains $HOME_ACTIONS "keyevent 223" "Sleep envia KEYCODE_SLEEP"
    stop_mirror
fi
rm -f $TMP/input $HOME_ACTIONS

# --- T14: Recents bypasses an app that consumes controller input ----------------------------------

echo ""
echo "=== T14: Recents recibe el control sin reenviarlo al app ==="

RECENTS_STATE=$TMP/recents.state
RECENTS_EVENTS=$TMP/recents.events
rm -f $RECENTS_STATE $RECENTS_EVENTS $TMP/t14.target
touch $RECENTS_EVENTS $RECENTS_STATE

start_mirror "$SRC" "$DST" --recents-state-file $RECENTS_STATE --recents-events-file $RECENTS_EVENTS --heartbeat-file $HEART
if [ -z "$(mirror_pid)" ]; then
    bad "el daemon arranco para probar el desvio de Recents"
else
    getevent -lt "$DST" > $TMP/t14.target 2>&1 &
    T14GE=$!
    sleep 0.4

    # Xbox pads commonly expose the D-pad as ABS_HAT0X; the button form is covered too.
    echo "abs 16 -32768" >&3; echo "syn" >&3
    echo "abs 16 0" >&3; echo "syn" >&3
    echo "key 547 1" >&3; echo "syn" >&3
    echo "key 547 0" >&3; echo "syn" >&3
    press_source 304
    press_source 308
    sleep 0.5
    kill $T14GE 2>/dev/null

    expect_contains $RECENTS_EVENTS "left" "el HAT izquierdo llega al servicio de Recents"
    expect_contains $RECENTS_EVENTS "right" "el D-pad derecho llega al servicio de Recents"
    expect_contains $RECENTS_EVENTS "resume" "A llega como Resume"
    expect_contains $RECENTS_EVENTS "close" "X llega como Close"
    if grep -qE "BTN_GAMEPAD|BTN_WEST|ABS_HAT0X" $TMP/t14.target 2>/dev/null; then
        bad "Recents no reenvia sus comandos al app capturador"
    else
        ok "Recents no reenvia sus comandos al app capturador"
    fi

    # Once overview closes, the very next source event follows the ordinary mirror path again.
    rm -f $RECENTS_STATE
    getevent -lt "$DST" > $TMP/t14.after 2>&1 &
    T14AFTER=$!
    sleep 0.3
    press_source 304
    sleep 0.4
    kill $T14AFTER 2>/dev/null
    expect_contains $TMP/t14.after "BTN_GAMEPAD" "al salir de Recents el pad vuelve al target"
    stop_mirror
fi
rm -f $RECENTS_STATE $RECENTS_EVENTS $TMP/t14.target $TMP/t14.after

# --- T15: source disconnect releases target state ------------------------------------------------

echo ""
echo "=== T15: desconectar el source libera botones y ejes del target ==="

start_mirror "$SRC" "$DST" --heartbeat-file $HEART
T15_MIRROR_PID=$(mirror_pid)
if [ -z "$T15_MIRROR_PID" ]; then
    bad "el daemon arranco para probar la desconexion"
else
    ok "el daemon arranco para probar la desconexion"

    getevent -lt "$DST" > $TMP/t15.out 2>&1 &
    T15GE=$!
    sleep 0.4

    # Leave both controls active, then destroy the source device without sending either release.
    echo "key 304 1" >&3
    echo "abs 0 24000" >&3
    echo "syn" >&3
    sleep 0.4
fi

echo "quit" >&3
exec 3>&-
wait "$SRC_PAD_PID" 2>/dev/null

if [ -n "$T15_MIRROR_PID" ]; then
    wait_for_gone "/proc/$T15_MIRROR_PID" 50
    sleep 0.4
    kill $T15GE 2>/dev/null

    if [ -z "$(mirror_pid)" ]; then
        ok "el daemon sale cuando desaparece el source"
    else
        bad "el daemon sigue vivo despues de desaparecer el source"
        stop_mirror
    fi
    expect_contains $TMP/t15.out "BTN_GAMEPAD.*DOWN" "el boton estaba apretado antes de desconectar"
    expect_contains $TMP/t15.out "BTN_GAMEPAD.*UP" "la desconexion suelta el boton en el target"
    expect_contains $TMP/t15.out "ABS_X.*00005dc0" "el eje estaba desviado antes de desconectar"
    expect_contains $TMP/t15.out "ABS_X.*00000000" "la desconexion devuelve el eje al centro"
fi
rm -f $TMP/t15.out

# --- teardown ------------------------------------------------------------------------------------

echo ""
echo "=== teardown ==="
stop_mirror
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
