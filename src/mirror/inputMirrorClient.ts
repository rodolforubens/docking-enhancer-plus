import {NativeModules} from 'react-native';
import type {InputDevice, MirrorStatus} from './types';

type InputMirrorNative = {
  startMirror(
    source: string,
    target: string,
    homeAsBack: boolean,
    comboHoldKillApp: boolean,
    sourceGuid?: string | null,
    targetGuid?: string | null,
  ): Promise<string>;
  stopMirror(): Promise<string>;
  getConnectedDevices(): Promise<InputDevice[]>;
  getMirrorStatus(): Promise<MirrorStatus>;
  getMirrorStatusVerified(): Promise<MirrorStatus>;
  setHomeAsBackEnabled(enabled: boolean): Promise<boolean>;
  setComboHoldKillAppEnabled(enabled: boolean): Promise<boolean>;
  setAutoRestartEnabled(enabled: boolean): Promise<boolean>;
  setAutoMirrorEnabled(enabled: boolean): Promise<boolean>;
  setManualInternalController(guid: string | null): Promise<string | null>;
};

const {InputMirror} = NativeModules as {InputMirror: InputMirrorNative};

export const inputMirrorClient = {
  startMirror: (
    source: string,
    target: string,
    homeAsBack: boolean,
    comboHoldKillApp: boolean,
    sourceGuid?: string | null,
    targetGuid?: string | null,
  ) => InputMirror.startMirror(source, target, homeAsBack, comboHoldKillApp, sourceGuid, targetGuid),
  stopMirror: () => InputMirror.stopMirror(),
  getConnectedDevices: () => InputMirror.getConnectedDevices(),
  getMirrorStatus: () => InputMirror.getMirrorStatus(),
  getMirrorStatusVerified: () => InputMirror.getMirrorStatusVerified(),
  setHomeAsBackEnabled: (enabled: boolean) => InputMirror.setHomeAsBackEnabled(enabled),
  setComboHoldKillAppEnabled: (enabled: boolean) => InputMirror.setComboHoldKillAppEnabled(enabled),
  setAutoRestartEnabled: (enabled: boolean) => InputMirror.setAutoRestartEnabled(enabled),
  setAutoMirrorEnabled: (enabled: boolean) => InputMirror.setAutoMirrorEnabled(enabled),
  setManualInternalController: (guid: string | null) => InputMirror.setManualInternalController(guid),
};
