export type InputDevice = {
  name: string;
  path: string;
  guid?: string;
  controllerNumber?: number;
  handlers?: string[];
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
};

export type Slot = 'local' | 'external';
