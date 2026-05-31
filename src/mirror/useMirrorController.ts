import {useCallback, useEffect, useMemo, useState} from 'react';
import {AppState, InteractionManager} from 'react-native';
import {inputMirrorClient} from './inputMirrorClient';
import type {InputDevice, MirrorStatus, Slot} from './types';

export function useMirrorController() {
  const [devices, setDevices] = useState<InputDevice[]>([]);
  const [localDevice, setLocalDevice] = useState<InputDevice | null>(null);
  const [externalDevice, setExternalDevice] = useState<InputDevice | null>(null);
  const [activeSlot, setActiveSlot] = useState<Slot | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [homeAsBack, setHomeAsBack] = useState(false);
  const [comboHoldKillApp, setComboHoldKillApp] = useState(false);
  const [autoRestart, setAutoRestart] = useState(false);
  const [restartWaiting, setRestartWaiting] = useState(false);

  const applyMirrorStatus = useCallback((status: MirrorStatus, availableDevices: InputDevice[]) => {
    const shouldShowEnabled = Boolean(status.running || (status.autoRestart && status.expectedRunning));
    setEnabled(shouldShowEnabled);
    setHomeAsBack(Boolean(status.homeAsBack));
    setComboHoldKillApp(Boolean(status.comboHoldKillApp));
    setAutoRestart(Boolean(status.autoRestart));

    if (!shouldShowEnabled) {
      return;
    }

    setExternalDevice(current =>
      deviceFromSavedIdentity(status.source, status.sourceGuid, availableDevices, current, 'Saved external controller'),
    );
    setLocalDevice(current =>
      deviceFromSavedIdentity(status.target, status.targetGuid, availableDevices, current, 'Saved local controller'),
    );
  }, []);

  const refreshDevices = useCallback(
    async (verifyWithRoot = false) => {
      const [result, status] = await Promise.all([
        inputMirrorClient.getConnectedDevices(),
        verifyWithRoot ? inputMirrorClient.getMirrorStatusVerified() : inputMirrorClient.getMirrorStatus(),
      ]);
      setDevices(result);
      applyMirrorStatus(status, result);

      if (!status.running) {
        setLocalDevice(current => keepSelectedDevice(current, result));
        setExternalDevice(current => keepSelectedDevice(current, result));
      }

      setMessage(result.length ? 'Controllers detected.' : 'No controllers detected.');
    },
    [applyMirrorStatus],
  );

  const refreshMirrorState = useCallback(async () => {
    const status = await inputMirrorClient.getMirrorStatus();
    applyMirrorStatus(status, devices);
    setRestartWaiting(Boolean(status.autoRestart && status.expectedRunning && !status.running));
  }, [applyMirrorStatus, devices]);

  useEffect(() => {
    let mounted = true;

    async function initialLoad() {
      let loadedDevices: InputDevice[] = [];
      try {
        const [result, status] = await Promise.all([
          inputMirrorClient.getConnectedDevices(),
          inputMirrorClient.getMirrorStatus(),
        ]);
        if (!mounted) {
          return;
        }

        loadedDevices = result;
        setDevices(result);
        applyMirrorStatus(status, result);
        setMessage(result.length ? 'Controllers detected.' : 'No controllers detected.');
      } catch (error) {
        setMessage(error instanceof Error ? error.message : String(error));
      } finally {
        if (mounted) {
          setLoading(false);
        }
      }

      if (!mounted) {
        return;
      }

      inputMirrorClient
        .getMirrorStatus()
        .then(status => {
          if (mounted) {
            applyMirrorStatus(status, loadedDevices);
            setRestartWaiting(Boolean(status.autoRestart && status.expectedRunning && !status.running));
          }
        })
        .catch(() => undefined);
    }

    const interactionTask = InteractionManager.runAfterInteractions(initialLoad);
    return () => {
      mounted = false;
      interactionTask.cancel();
    };
  }, [applyMirrorStatus]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', state => {
      if (state === 'active') {
        refreshDevices(false).catch(error => setMessage(error instanceof Error ? error.message : String(error)));
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

  const modalTitle = activeSlot === 'local' ? 'Local Controller' : 'External Controller';

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

  async function toggleHomeAsBack(nextValue: boolean) {
    if (enabled || busy) {
      return;
    }

    setHomeAsBack(nextValue);
    try {
      await inputMirrorClient.setHomeAsBackEnabled(nextValue);
    } catch (error) {
      setHomeAsBack(!nextValue);
      setMessage(error instanceof Error ? error.message : String(error));
    }
  }

  async function toggleComboHoldKillApp(nextValue: boolean) {
    if (enabled || busy) {
      return;
    }

    setComboHoldKillApp(nextValue);
    try {
      await inputMirrorClient.setComboHoldKillAppEnabled(nextValue);
    } catch (error) {
      setComboHoldKillApp(!nextValue);
      setMessage(error instanceof Error ? error.message : String(error));
    }
  }

  async function toggleAutoRestart(nextValue: boolean) {
    if (enabled || busy) {
      return;
    }

    setAutoRestart(nextValue);
    if (!nextValue) {
      setRestartWaiting(false);
    }

    try {
      await inputMirrorClient.setAutoRestartEnabled(nextValue);
    } catch (error) {
      setAutoRestart(!nextValue);
      setMessage(error instanceof Error ? error.message : String(error));
    }
  }

  async function toggleMirror() {
    if (!canToggle || busy) {
      return;
    }

    setBusy(true);
    try {
      if (enabled) {
        await inputMirrorClient.stopMirror();
        setEnabled(false);
        setMessage('Mirror stopped.');
      } else {
        if (!localDevice || !externalDevice) {
          setMessage('Select different local and external controllers.');
          return;
        }

        setEnabled(true);
        setMessage('Mirror active.');
        await inputMirrorClient.startMirror(
          externalDevice.path,
          localDevice.path,
          homeAsBack,
          comboHoldKillApp,
          externalDevice.guid ?? null,
          localDevice.guid ?? null,
        );
        setRestartWaiting(false);
      }
    } catch (error) {
      setEnabled(false);
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  return {
    devices,
    localDevice,
    externalDevice,
    activeSlot,
    enabled,
    loading,
    busy,
    message,
    homeAsBack,
    comboHoldKillApp,
    autoRestart,
    restartWaiting,
    canStart,
    canToggle,
    modalTitle,
    openDeviceModal,
    selectedDeviceFor,
    selectDevice,
    setActiveSlot,
    toggleHomeAsBack,
    toggleComboHoldKillApp,
    toggleAutoRestart,
    toggleMirror,
  };
}

function keepSelectedDevice(current: InputDevice | null, devices: InputDevice[]) {
  if (!current) {
    return null;
  }

  return devices.find(device => device.path === current.path) ?? null;
}

function findDeviceBySavedIdentity(path: string | null | undefined, guid: string | null | undefined, devices: InputDevice[]) {
  if (guid) {
    const guidMatch = devices.find(device => device.guid === guid);
    if (guidMatch) {
      return guidMatch;
    }
  }

  if (path) {
    return devices.find(device => device.path === path) ?? null;
  }

  return null;
}

function deviceFromSavedIdentity(
  path: string | null | undefined,
  guid: string | null | undefined,
  devices: InputDevice[],
  current: InputDevice | null,
  fallbackName: string,
) {
  const found = findDeviceBySavedIdentity(path, guid, devices);
  if (found) {
    return found;
  }

  if (current && ((guid && current.guid === guid) || (path && current.path === path))) {
    return current;
  }

  if (!path && !guid) {
    return null;
  }

  return {name: fallbackName, path: path ?? '', guid: guid ?? undefined};
}
