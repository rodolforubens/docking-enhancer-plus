import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {
  ActivityIndicator,
  AppState,
  Modal,
  NativeModules,
  Pressable,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {IconDeviceGamepad2} from '@tabler/icons-react-native';

type InputDevice = {
  name: string;
  path: string;
  handlers?: string[];
};

type MirrorStatus = {
  running: boolean;
  source?: string | null;
  target?: string | null;
};

type InputMirrorNative = {
  startMirror(source: string, target: string): Promise<string>;
  stopMirror(): Promise<string>;
  getConnectedDevices(): Promise<InputDevice[]>;
  isMirrorRunning(): Promise<boolean>;
  getMirrorStatus(): Promise<MirrorStatus>;
};

type Slot = 'local' | 'external';

type PressableFocusState = {
  focused?: boolean;
  pressed?: boolean;
  hovered?: boolean;
};

const {InputMirror} = NativeModules as {InputMirror: InputMirrorNative};

export default function App() {
  const [devices, setDevices] = useState<InputDevice[]>([]);
  const [localDevice, setLocalDevice] = useState<InputDevice | null>(null);
  const [externalDevice, setExternalDevice] = useState<InputDevice | null>(null);
  const [activeSlot, setActiveSlot] = useState<Slot | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const applyMirrorStatus = useCallback((status: MirrorStatus, availableDevices: InputDevice[]) => {
    setEnabled(status.running);

    if (!status.running) {
      return;
    }

    setExternalDevice(deviceFromSavedPath(status.source, availableDevices, 'Mando externo guardado'));
    setLocalDevice(deviceFromSavedPath(status.target, availableDevices, 'Mando local guardado'));
  }, []);

  const refreshDevices = useCallback(async () => {
    const [result, status] = await Promise.all([
      InputMirror.getConnectedDevices(),
      InputMirror.getMirrorStatus(),
    ]);
    setDevices(result);
    applyMirrorStatus(status, result);

    if (!status.running) {
      setLocalDevice(current => keepSelectedDevice(current, result));
      setExternalDevice(current => keepSelectedDevice(current, result));
    }

    setMessage(result.length ? 'Dispositivos detectados.' : 'No se detectaron mandos.');
  }, [applyMirrorStatus]);

  const refreshMirrorState = useCallback(async () => {
    const status = await InputMirror.getMirrorStatus();
    applyMirrorStatus(status, devices);
  }, [applyMirrorStatus, devices]);

  useEffect(() => {
    let mounted = true;

    async function initialLoad() {
      try {
        const [result, status] = await Promise.all([
          InputMirror.getConnectedDevices(),
          InputMirror.getMirrorStatus(),
        ]);
        if (!mounted) {
          return;
        }

        setDevices(result);
        applyMirrorStatus(status, result);
        setMessage(result.length ? 'Dispositivos detectados.' : 'No se detectaron mandos.');
      } catch (error) {
        setMessage(error instanceof Error ? error.message : String(error));
      } finally {
        if (mounted) {
          setLoading(false);
        }
      }
    }

    initialLoad();
    return () => {
      mounted = false;
    };
  }, [applyMirrorStatus]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') {
        refreshDevices().catch(error => setMessage(error instanceof Error ? error.message : String(error)));
        refreshMirrorState().catch(() => undefined);
      }
    });

    const interval = setInterval(() => {
      if (AppState.currentState === 'active') {
        refreshMirrorState().catch(() => undefined);
      }
    }, 2000);

    return () => {
      subscription.remove();
      clearInterval(interval);
    };
  }, [refreshDevices, refreshMirrorState]);

  useEffect(() => {
    if (activeSlot !== null) {
      refreshDevices().catch(error => setMessage(error instanceof Error ? error.message : String(error)));
    }
  }, [activeSlot, refreshDevices]);

  const canStart = useMemo(() => {
    return Boolean(localDevice && externalDevice && localDevice.path !== externalDevice.path);
  }, [localDevice, externalDevice]);
  const canToggle = enabled || canStart;

  const modalTitle = activeSlot === 'local' ? 'Mando Local' : 'Mando Externo';

  function selectedDeviceFor(slot: Slot | null) {
    if (slot === 'local') {
      return localDevice;
    }
    if (slot === 'external') {
      return externalDevice;
    }
    return null;
  }

  function selectDevice(device: InputDevice) {
    if (activeSlot === 'local') {
      setLocalDevice(device);
    }
    if (activeSlot === 'external') {
      setExternalDevice(device);
    }
    setActiveSlot(null);
  }

  function openDeviceModal(slot: Slot) {
    setActiveSlot(slot);
  }

  async function toggleMirror() {
    if (!canToggle || busy) {
      return;
    }

    setBusy(true);
    try {
      if (enabled) {
        await InputMirror.stopMirror();
        setEnabled(false);
        setMessage('Espejo detenido.');
      } else {
        if (!localDevice || !externalDevice) {
          setMessage('Selecciona un mando local y uno externo distintos.');
          return;
        }

        await InputMirror.startMirror(externalDevice.path, localDevice.path);
        setEnabled(true);
        setMessage('Espejo activo.');
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="light-content" backgroundColor="#0b0c0e" />
      <View style={styles.container}>
        <View style={styles.header}>
          <Text style={styles.title}>Odin Input Mirror</Text>
          <Text style={[styles.status, enabled ? styles.statusOn : styles.statusOff]}>
            {enabled ? 'ACTIVO' : 'INACTIVO'}
          </Text>
        </View>

        <Text style={styles.subtitle}>Selecciona un mando para configurar</Text>

        {loading ? (
          <View style={styles.loading}>
            <ActivityIndicator color="#87a7d7" />
            <Text style={styles.muted}>Buscando mandos...</Text>
          </View>
        ) : (
          <>
            <View style={styles.grid}>
              <DeviceCard
                title="Mando Local"
                device={localDevice}
                selected={activeSlot === 'local'}
                preferredFocus
                onPress={() => openDeviceModal('local')}
              />
              <DeviceCard
                title="Mando Externo"
                device={externalDevice}
                selected={activeSlot === 'external'}
                onPress={() => openDeviceModal('external')}
              />
            </View>

            <Pressable
              disabled={!canToggle || busy}
              onPress={toggleMirror}
              style={({focused, pressed}: PressableFocusState) => [
                styles.button,
                enabled ? styles.buttonStop : styles.buttonStart,
                (!canToggle || busy) && styles.buttonDisabled,
                focused && styles.focused,
                pressed && styles.buttonPressed,
              ]}>
              <Text style={styles.buttonText}>
                {busy ? 'Procesando...' : enabled ? 'Desactivar Espejo' : 'Activar Espejo'}
              </Text>
            </Pressable>

            <Text style={styles.message}>{message}</Text>
            {!enabled && !canStart && (
              <Text style={styles.hint}>Selecciona un mando local y uno externo distintos.</Text>
            )}
          </>
        )}
      </View>

      <DeviceModal
        visible={activeSlot !== null}
        title={modalTitle}
        devices={devices}
        selectedPath={selectedDeviceFor(activeSlot)?.path}
        onClose={() => setActiveSlot(null)}
        onSelect={selectDevice}
      />
    </SafeAreaView>
  );
}

function DeviceCard({
  title,
  device,
  selected,
  preferredFocus,
  onPress,
}: {
  title: string;
  device: InputDevice | null;
  selected: boolean;
  preferredFocus?: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      focusable
      hasTVPreferredFocus={preferredFocus}
      onPress={onPress}
      style={({focused}: PressableFocusState) => [styles.card, selected && styles.cardSelected, focused && styles.focused]}>
      <IconDeviceGamepad2 color="#61718a" size={40} strokeWidth={2.2} style={styles.gamepadIcon} />
      <Text style={styles.cardTitle}>{title}</Text>
      <Text numberOfLines={1} style={device ? styles.cardDevice : styles.cardEmpty}>
        {device?.name ?? 'Sin dispositivo'}
      </Text>
    </Pressable>
  );
}

function DeviceModal({
  visible,
  title,
  devices,
  selectedPath,
  onClose,
  onSelect,
}: {
  visible: boolean;
  title: string;
  devices: InputDevice[];
  selectedPath?: string;
  onClose: () => void;
  onSelect: (device: InputDevice) => void;
}) {
  return (
    <Modal transparent visible={visible} animationType="fade" onRequestClose={onClose}>
      <Pressable focusable={false} style={styles.overlay} onPress={onClose}>
        <Pressable focusable={false} style={styles.modal} onPress={() => undefined}>
          <Text style={styles.modalTitle}>{title}</Text>
          <ScrollView style={styles.modalList} contentContainerStyle={styles.modalListContent}>
            {devices.map((device, index) => {
              const selected = device.path === selectedPath;
              return (
                <Pressable
                  key={device.path}
                  focusable
                  hasTVPreferredFocus={index === 0}
                  onPress={() => onSelect(device)}
                  style={({focused}: PressableFocusState) => [styles.option, focused && styles.optionFocused]}>
                  <View style={[styles.radio, selected && styles.radioSelected]}>
                    {selected && <View style={styles.radioDot} />}
                  </View>
                  <View style={styles.optionText}>
                    <Text numberOfLines={1} style={styles.optionName}>
                      {device.name}
                    </Text>
                    <Text numberOfLines={1} style={styles.optionPath}>
                      {device.path}
                    </Text>
                  </View>
                </Pressable>
              );
            })}
            {devices.length === 0 && <Text style={styles.emptyModal}>No se detectaron mandos.</Text>}
          </ScrollView>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

function keepSelectedDevice(current: InputDevice | null, devices: InputDevice[]) {
  if (!current) {
    return null;
  }

  return devices.find(device => device.path === current.path) ?? null;
}

function deviceFromSavedPath(path: string | null | undefined, devices: InputDevice[], fallbackName: string) {
  if (!path) {
    return null;
  }

  return devices.find(device => device.path === path) ?? {name: fallbackName, path};
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#0b0c0e',
  },
  container: {
    flex: 1,
    paddingHorizontal: 22,
    paddingTop: 28,
    paddingBottom: 20,
  },
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 34,
  },
  title: {
    color: '#f4f7fb',
    fontSize: 24,
    fontWeight: '700',
  },
  status: {
    borderRadius: 7,
    fontSize: 12,
    fontWeight: '800',
    overflow: 'hidden',
    paddingHorizontal: 12,
    paddingVertical: 7,
  },
  statusOn: {
    backgroundColor: '#123621',
    color: '#69e18d',
  },
  statusOff: {
    backgroundColor: '#3b1719',
    color: '#ff7878',
  },
  subtitle: {
    color: '#8fa0bc',
    fontSize: 14,
    marginBottom: 12,
  },
  grid: {
    flexDirection: 'row',
    gap: 12,
    marginBottom: 28,
  },
  card: {
    alignItems: 'center',
    backgroundColor: '#1a1b22',
    borderColor: '#2c2f3b',
    borderRadius: 12,
    borderWidth: 1,
    flex: 1,
    height: 150,
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  cardSelected: {
    borderColor: '#6b86ad',
  },
  focused: {
    borderColor: '#9ec5ff',
    backgroundColor: '#202331',
  },
  gamepadIcon: {
    marginBottom: 14,
  },
  cardTitle: {
    color: '#a8c8f3',
    fontSize: 13,
    fontWeight: '700',
    marginBottom: 16,
  },
  cardEmpty: {
    color: '#58647a',
    fontSize: 12,
  },
  cardDevice: {
    color: '#d9e6f8',
    fontSize: 12,
    fontWeight: '700',
    maxWidth: '100%',
  },
  button: {
    alignItems: 'center',
    borderColor: '#4a4b51',
    borderRadius: 7,
    borderWidth: 1,
    height: 36,
    justifyContent: 'center',
    marginBottom: 16,
  },
  buttonStart: {
    backgroundColor: 'transparent',
  },
  buttonStop: {
    backgroundColor: '#3d1618',
    borderColor: '#723033',
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  buttonPressed: {
    opacity: 0.82,
  },
  buttonText: {
    color: '#f7fbff',
    fontSize: 14,
    fontWeight: '800',
  },
  message: {
    color: '#8fa0bc',
    fontSize: 14,
    lineHeight: 20,
  },
  hint: {
    color: '#5f6d83',
    fontSize: 12,
    lineHeight: 18,
    marginTop: 6,
  },
  loading: {
    alignItems: 'center',
    flex: 1,
    gap: 14,
    justifyContent: 'center',
  },
  muted: {
    color: '#8fa0bc',
  },
  overlay: {
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.78)',
    flex: 1,
    justifyContent: 'center',
    padding: 22,
  },
  modal: {
    backgroundColor: '#1d1e27',
    borderColor: '#303343',
    borderRadius: 12,
    borderWidth: 1,
    maxHeight: 280,
    paddingHorizontal: 24,
    paddingVertical: 20,
    width: '100%',
    maxWidth: 258,
  },
  modalTitle: {
    color: '#91a0bc',
    fontSize: 14,
    fontWeight: '800',
    marginBottom: 14,
  },
  modalList: {
    maxHeight: 210,
  },
  modalListContent: {
    paddingBottom: 2,
  },
  option: {
    alignItems: 'center',
    borderRadius: 8,
    flexDirection: 'row',
    minHeight: 58,
    paddingHorizontal: 4,
  },
  optionFocused: {
    backgroundColor: '#282b39',
  },
  radio: {
    alignItems: 'center',
    borderColor: '#5b6175',
    borderRadius: 10,
    borderWidth: 2,
    height: 20,
    justifyContent: 'center',
    marginRight: 12,
    width: 20,
  },
  radioSelected: {
    borderColor: '#8fb3e9',
  },
  radioDot: {
    backgroundColor: '#8fb3e9',
    borderRadius: 5,
    height: 10,
    width: 10,
  },
  optionText: {
    borderBottomColor: '#2e3140',
    borderBottomWidth: 1,
    flex: 1,
    paddingBottom: 10,
    paddingTop: 10,
  },
  optionName: {
    color: '#f3f6fb',
    fontSize: 14,
    fontWeight: '800',
  },
  optionPath: {
    color: '#74839a',
    fontSize: 12,
    marginTop: 2,
  },
  emptyModal: {
    color: '#74839a',
    fontSize: 13,
    lineHeight: 18,
    paddingVertical: 12,
  },
});
