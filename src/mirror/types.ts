export type InputDevice = {
  name: string;
  path: string;
  guid?: string;
  controllerNumber?: number;
  handlers?: string[];
  isOdinInternal?: boolean;
};

export type MirrorStatus = {
  running: boolean;
  expectedRunning?: boolean;
  source?: string | null;
  target?: string | null;
  sourceGuid?: string | null;
  targetGuid?: string | null;
  homeAsBack?: boolean;
  comboHoldKillApp?: boolean;
  autoRestart?: boolean;
  autoMirrorEnabled?: boolean;
};

export type Slot = 'local' | 'external';
