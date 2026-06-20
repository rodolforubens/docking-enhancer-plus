import {useCallback, useEffect, useRef, useState} from 'react';
import {AppState, InteractionManager} from 'react-native';
import {inputMirrorClient} from './inputMirrorClient';
import type {InputDevice, MirrorStatus} from './types';

// Poll fast while a state change is imminent (waiting for a controller, or for the mirror to
// start/stop), and back off once the mirror is running steadily, where nothing changes between
// ticks — this avoids draining the handheld battery with a constant 2s cadence.
const ACTIVE_POLL_MS = 2000;
const IDLE_POLL_MS = 6000;

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
  const [docked, setDocked] = useState(false);
  const [manualInternalGuid, setManualInternalGuid] = useState<string | null>(null);

  // refreshDevices/refreshMirrorState/initialLoad can be triggered concurrently (AppState
  // change + the 2s poll interval). Each call claims the next id before awaiting, and only
  // applies its result if no newer call has started in the meantime, so a slow, stale
  // response can never overwrite state from a request that started later.
  const latestRequestId = useRef(0);

  // Desired poll cadence, read by the self-scheduling poll loop on each tick. Kept in a ref so
  // changing the cadence does not tear down and recreate the AppState listener / poll loop.
  const pollDelayRef = useRef(ACTIVE_POLL_MS);
  pollDelayRef.current = enabled && !restartWaiting ? IDLE_POLL_MS : ACTIVE_POLL_MS;

  const applyMirrorStatus = useCallback((status: MirrorStatus, availableDevices: InputDevice[]) => {
    setEnabled(Boolean(status.running));
    setHomeAsBack(Boolean(status.homeAsBack));
    setComboHoldKillApp(Boolean(status.comboHoldKillApp));
    setAutoMirrorEnabled(status.autoMirrorEnabled !== false);
    setRestartWaiting(Boolean(status.expectedRunning && !status.running));
    setDocked(Boolean(status.docked));
    setManualInternalGuid(status.manualInternalGuid ?? null);

    const autoLocal = availableDevices.find(device => device.isInternal) ?? null;
    const autoExternal = availableDevices.find(device => !device.isInternal) ?? null;
    const savedExternalIsLocal = autoLocal
      ? savedIdentityMatchesDevice(status.source, status.sourceGuid, autoLocal)
      : false;

    setLocalDevice(autoLocal ?? deviceFromSavedIdentity(status.target, status.targetGuid, availableDevices, 'Saved Odin controller'));
    setExternalDevice(
      autoExternal ??
        (savedExternalIsLocal
          ? null
          : deviceFromSavedIdentity(status.source, status.sourceGuid, availableDevices, 'Waiting for external controller')),
    );
  }, []);

  const refreshDevices = useCallback(
    async (verifyWithRoot = false) => {
      const requestId = ++latestRequestId.current;
      const [result, status] = await Promise.all([
        inputMirrorClient.getConnectedDevices(),
        verifyWithRoot ? inputMirrorClient.getMirrorStatusVerified() : inputMirrorClient.getMirrorStatus(),
      ]);
      if (requestId !== latestRequestId.current) {
        return;
      }
      setDevices(result);
      applyMirrorStatus(status, result);
      setMessage(buildStatusMessage(status, result));
    },
    [applyMirrorStatus],
  );

  const refreshMirrorState = useCallback(async () => {
    const requestId = ++latestRequestId.current;
    const [result, status] = await Promise.all([inputMirrorClient.getConnectedDevices(), inputMirrorClient.getMirrorStatus()]);
    if (requestId !== latestRequestId.current) {
      return;
    }
    setDevices(result);
    applyMirrorStatus(status, result);
    setMessage(buildStatusMessage(status, result));
  }, [applyMirrorStatus]);

  useEffect(() => {
    let mounted = true;

    async function initialLoad() {
      const requestId = ++latestRequestId.current;
      try {
        const [result, status] = await Promise.all([inputMirrorClient.getConnectedDevices(), inputMirrorClient.getMirrorStatus()]);
        if (!mounted || requestId !== latestRequestId.current) {
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

    let timeout: ReturnType<typeof setTimeout>;
    const scheduleNext = () => {
      timeout = setTimeout(async () => {
        if (AppState.currentState === 'active') {
          await refreshMirrorState().catch(() => undefined);
        }
        scheduleNext();
      }, pollDelayRef.current);
    };
    scheduleNext();

    return () => {
      subscription.remove();
      clearTimeout(timeout);
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

  async function selectInternalController(guid: string | null) {
    if (busy) {
      return;
    }

    setBusy(true);
    try {
      await inputMirrorClient.setManualInternalController(guid);
      setManualInternalGuid(guid);
      await refreshDevices(false);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }

  // On a recognised handheld (e.g. Odin) the native layer flags the internal controller by
  // hardware signature and locks it. Otherwise the internal defaults to the first detected
  // controller and the user can re-pick it manually.
  const hasKnownInternalProfile = devices.some(device => device.isKnownInternal);

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
    docked,
    manualInternalGuid,
    hasKnownInternalProfile,
    toggleHomeAsBack,
    toggleComboHoldKillApp,
    toggleAutoMirrorEnabled,
    selectInternalController,
  };
}

function buildStatusMessage(status: MirrorStatus, devices: InputDevice[]) {
  if (status.running) {
    return 'Dock mirror active.';
  }

  if (status.autoMirrorEnabled === false) {
    return 'Automatic dock mirror is disabled.';
  }

  if (!devices.some(device => device.isInternal)) {
    return 'Waiting for Odin internal controller.';
  }

  if (!devices.some(device => !device.isInternal)) {
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

function savedIdentityMatchesDevice(path: string | null | undefined, guid: string | null | undefined, device: InputDevice) {
  return Boolean((guid && device.guid === guid) || (path && device.path === path));
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
