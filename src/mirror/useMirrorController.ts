import {useCallback, useEffect, useState} from 'react';
import {AppState, InteractionManager} from 'react-native';
import {inputMirrorClient} from './inputMirrorClient';
import type {InputDevice, MirrorStatus} from './types';

export function useMirrorController() {
  const [devices, setDevices] = useState<InputDevice[]>([]);
  const [localDevice, setLocalDevice] = useState<InputDevice | null>(null);
  const [externalDevice, setExternalDevice] = useState<InputDevice | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [homeAsBack, setHomeAsBack] = useState(false);
  const [comboHoldKillApp, setComboHoldKillApp] = useState(false);
  const [autoMirrorEnabled, setAutoMirrorEnabled] = useState(true);
  const [restartWaiting, setRestartWaiting] = useState(false);

  const applyMirrorStatus = useCallback((status: MirrorStatus, availableDevices: InputDevice[]) => {
    setEnabled(Boolean(status.running));
    setHomeAsBack(Boolean(status.homeAsBack));
    setComboHoldKillApp(Boolean(status.comboHoldKillApp));
    setAutoMirrorEnabled(status.autoMirrorEnabled !== false);
    setRestartWaiting(Boolean(status.expectedRunning && !status.running));

    const autoLocal = availableDevices.find(device => device.isOdinInternal) ?? null;
    const autoExternal = availableDevices.find(device => !device.isOdinInternal) ?? null;

    setLocalDevice(autoLocal ?? deviceFromSavedIdentity(status.target, status.targetGuid, availableDevices, 'Saved Odin controller'));
    setExternalDevice(
      autoExternal ?? deviceFromSavedIdentity(status.source, status.sourceGuid, availableDevices, 'Waiting for external controller'),
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
      setMessage(buildStatusMessage(status, result));
    },
    [applyMirrorStatus],
  );

  const refreshMirrorState = useCallback(async () => {
    const [result, status] = await Promise.all([inputMirrorClient.getConnectedDevices(), inputMirrorClient.getMirrorStatus()]);
    setDevices(result);
    applyMirrorStatus(status, result);
    setMessage(buildStatusMessage(status, result));
  }, [applyMirrorStatus]);

  useEffect(() => {
    let mounted = true;

    async function initialLoad() {
      try {
        const [result, status] = await Promise.all([inputMirrorClient.getConnectedDevices(), inputMirrorClient.getMirrorStatus()]);
        if (!mounted) {
          return;
        }

        setDevices(result);
        applyMirrorStatus(status, result);
        setMessage(buildStatusMessage(status, result));
      } catch (error) {
        setMessage(error instanceof Error ? error.message : String(error));
      } finally {
        if (mounted) {
          setLoading(false);
        }
      }
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

  async function toggleAutoMirrorEnabled(nextValue: boolean) {
    if (busy) {
      return;
    }

    setBusy(true);
    setAutoMirrorEnabled(nextValue);
    try {
      await inputMirrorClient.setAutoMirrorEnabled(nextValue);
      setEnabled(current => (nextValue ? current : false));
      setMessage(nextValue ? 'Automatic dock mirror is enabled.' : 'Automatic dock mirror is disabled.');
    } catch (error) {
      setAutoMirrorEnabled(!nextValue);
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  return {
    devices,
    localDevice,
    externalDevice,
    enabled,
    loading,
    busy,
    message,
    homeAsBack,
    comboHoldKillApp,
    autoMirrorEnabled,
    restartWaiting,
    toggleHomeAsBack,
    toggleComboHoldKillApp,
    toggleAutoMirrorEnabled,
  };
}

function buildStatusMessage(status: MirrorStatus, devices: InputDevice[]) {
  if (status.running) {
    return 'Dock mirror active.';
  }

  if (status.autoMirrorEnabled === false) {
    return 'Automatic dock mirror is disabled.';
  }

  if (!devices.some(device => device.isOdinInternal)) {
    return 'Waiting for Odin internal controller.';
  }

  if (!devices.some(device => !device.isOdinInternal)) {
    return 'Waiting for an external controller.';
  }

  if (status.expectedRunning) {
    return 'Controller detected. Mirror will start automatically.';
  }

  return 'Automatic dock mirror is ready.';
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
  fallbackName: string,
) {
  const found = findDeviceBySavedIdentity(path, guid, devices);
  if (found) {
    return found;
  }

  if (!path && !guid) {
    return null;
  }

  return {name: fallbackName, path: path ?? '', guid: guid ?? undefined};
}
