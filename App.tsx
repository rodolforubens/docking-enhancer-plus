import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  findNodeHandle,
  Pressable,
  SafeAreaView,
  ScrollView,
  StatusBar,
  Switch,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {IconDeviceGamepad2} from '@tabler/icons-react-native';
import {useMirrorController} from './src/mirror/useMirrorController';
import type {InputDevice} from './src/mirror/types';
import {palette, radius, spacing} from './src/theme/tokens';

type PressableFocusState = {
  focused?: boolean;
  pressed?: boolean;
  hovered?: boolean;
};

type TvFocusProps = {
  nextFocusUp?: number;
  nextFocusDown?: number;
  nextFocusLeft?: number;
  nextFocusRight?: number;
};

export default function App() {
  const localCardRef = useRef<View>(null);
  const externalCardRef = useRef<View>(null);
  const homeRowRef = useRef<View>(null);
  const comboRowRef = useRef<View>(null);
  const autoMirrorButtonRef = useRef<View>(null);
  const [focusIds, setFocusIds] = useState<Record<string, number>>({});

  const {
    localDevice,
    externalDevice,
    enabled,
    loading,
    busy,
    message,
    autoMirrorEnabled,
    homeAsBack,
    comboHoldKillApp,
    restartWaiting,
    toggleAutoMirrorEnabled,
    toggleHomeAsBack,
    toggleComboHoldKillApp,
  } = useMirrorController();

  useEffect(() => {
    const updateFocusIds = () => {
      setFocusIds({
        local: findNodeHandle(localCardRef.current) ?? 0,
        external: findNodeHandle(externalCardRef.current) ?? 0,
        home: findNodeHandle(homeRowRef.current) ?? 0,
        combo: findNodeHandle(comboRowRef.current) ?? 0,
        autoMirror: findNodeHandle(autoMirrorButtonRef.current) ?? 0,
      });
    };

    const timeout = setTimeout(updateFocusIds, 0);
    return () => clearTimeout(timeout);
  }, []);

  return (
    <SafeAreaView style={styles.screen}>
      <StatusBar barStyle="light-content" backgroundColor="#0b0c0e" />
      <ScrollView
        style={styles.scroller}
        contentContainerStyle={styles.container}
        keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <View style={styles.titleRow}>
            <Text style={styles.title}>Docking Enhancer</Text>
            {loading && <ActivityIndicator color="#87a7d7" size="small" />}
          </View>
          <Text style={[styles.status, enabled ? styles.statusOn : styles.statusOff]}>
            {enabled ? 'ACTIVE' : 'INACTIVE'}
          </Text>
        </View>

        <View style={styles.sectionHeader}>
          <Text style={styles.sectionTitle}>Automatic Dock Mirror</Text>
          <Text style={styles.subtitle}>Controllers are selected automatically while dock mode is active.</Text>
        </View>

        <View style={styles.grid}>
          <DeviceCard
            pressableRef={localCardRef}
            title="Local Controller"
            device={localDevice}
            hint="Odin internal · locked"
            preferredFocus
            tvFocus={{nextFocusRight: focusIds.external, nextFocusDown: focusIds.home}}
          />
          <DeviceCard
            pressableRef={externalCardRef}
            title="External Controller"
            device={externalDevice}
            hint="First connected controller"
            tvFocus={{nextFocusLeft: focusIds.local, nextFocusDown: focusIds.home}}
          />
        </View>

        <Pressable
          focusable
          disabled={enabled || busy}
          ref={homeRowRef}
          {...({nextFocusUp: focusIds.local, nextFocusDown: focusIds.combo} as any)}
          accessibilityRole="switch"
          accessibilityLabel="Home as Back"
          accessibilityState={{disabled: enabled || busy, checked: homeAsBack}}
          onPress={() => toggleHomeAsBack(!homeAsBack)}
          style={({focused, pressed}: PressableFocusState) => [
            styles.settingRow,
            focused && styles.focused,
            pressed && styles.buttonPressed,
            (enabled || busy) && styles.settingDisabled,
          ]}>
          <View style={styles.settingText}>
            <Text style={styles.settingTitle}>Home as Back</Text>
            <Text style={styles.settingDescription}>External controller Home button acts as Back</Text>
          </View>
          <Switch
            disabled={enabled || busy}
            value={homeAsBack}
            onValueChange={toggleHomeAsBack}
            trackColor={{false: '#2b2d38', true: '#1585C3'}}
            thumbColor={homeAsBack ? '#d7f1ff' : '#7e8494'}
          />
        </Pressable>

        <Pressable
          focusable
          disabled={enabled || busy}
          ref={comboRowRef}
          {...({nextFocusUp: focusIds.home, nextFocusDown: focusIds.autoMirror} as any)}
          accessibilityRole="switch"
          accessibilityLabel="Select plus Start closes app"
          accessibilityState={{disabled: enabled || busy, checked: comboHoldKillApp}}
          onPress={() => toggleComboHoldKillApp(!comboHoldKillApp)}
          style={({focused, pressed}: PressableFocusState) => [
            styles.settingRow,
            focused && styles.focused,
            pressed && styles.buttonPressed,
            (enabled || busy) && styles.settingDisabled,
          ]}>
          <View style={styles.settingText}>
            <Text style={styles.settingTitle}>Select + Start closes app</Text>
            <Text style={styles.settingDescription}>Hold Select and Start for 3 seconds to close the current app</Text>
          </View>
          <Switch
            disabled={enabled || busy}
            value={comboHoldKillApp}
            onValueChange={toggleComboHoldKillApp}
            trackColor={{false: '#2b2d38', true: '#1585C3'}}
            thumbColor={comboHoldKillApp ? '#d7f1ff' : '#7e8494'}
          />
        </Pressable>

        <Text style={styles.message}>
          {loading ? 'Scanning controllers...' : restartWaiting ? 'Waiting for automatic mirror start...' : message}
        </Text>
        {!loading && !enabled && autoMirrorEnabled && (
          <Text style={styles.hint}>The mirror starts automatically when the Odin controller and an external controller are available.</Text>
        )}

        <Pressable
          disabled={busy}
          ref={autoMirrorButtonRef}
          {...({nextFocusUp: focusIds.combo} as any)}
          accessibilityRole="button"
          accessibilityLabel={autoMirrorEnabled ? 'Turn off automatic mirror' : 'Turn on automatic mirror'}
          accessibilityState={{disabled: busy}}
          onPress={() => toggleAutoMirrorEnabled(!autoMirrorEnabled)}
          style={({focused, pressed}: PressableFocusState) => [
            styles.button,
            autoMirrorEnabled ? styles.buttonStop : styles.buttonStart,
            busy && styles.buttonDisabled,
            focused && styles.focused,
            pressed && styles.buttonPressed,
          ]}>
          <Text style={styles.buttonText}>
            {busy ? 'Processing...' : autoMirrorEnabled ? 'Turn Off Automatic Mirror' : 'Turn On Automatic Mirror'}
          </Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

function DeviceCard({
  pressableRef,
  title,
  device,
  hint,
  preferredFocus,
  tvFocus,
}: {
  pressableRef?: React.Ref<View>;
  title: string;
  device: InputDevice | null;
  hint: string;
  preferredFocus?: boolean;
  tvFocus?: TvFocusProps;
}) {
  return (
    <Pressable
      ref={pressableRef}
      focusable
      hasTVPreferredFocus={preferredFocus}
      {...(tvFocus as any)}
      accessibilityRole="text"
      accessibilityLabel={`${title}. ${device?.name ?? 'No device detected'}`}
      style={({focused}: PressableFocusState) => [styles.card, device && styles.cardSelected, focused && styles.focused]}>
      <IconDeviceGamepad2 color="#61718a" size={40} strokeWidth={2.2} style={styles.gamepadIcon} />
      <Text style={styles.cardTitle}>{title}</Text>
      <Text style={styles.cardHint}>{hint}</Text>
      <Text numberOfLines={1} style={device ? styles.cardDevice : styles.cardEmpty}>
        {device?.name ?? 'No device'}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: palette.screen,
  },
  scroller: {
    flex: 1,
  },
  container: {
    flexGrow: 1,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xxl,
    paddingBottom: spacing.xxxl,
  },
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 24,
  },
  titleRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
  },
  title: {
    color: palette.textPrimary,
    fontSize: 26,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  status: {
    borderRadius: radius.pill,
    fontSize: 12,
    fontWeight: '800',
    overflow: 'hidden',
    paddingHorizontal: 14,
    paddingVertical: spacing.sm,
  },
  statusOn: {
    backgroundColor: palette.accentSoft,
    color: palette.accent,
  },
  statusOff: {
    backgroundColor: palette.dangerSoft,
    color: palette.danger,
  },
  sectionHeader: {
    marginBottom: spacing.md,
  },
  sectionTitle: {
    color: palette.textPrimary,
    fontSize: 17,
    fontWeight: '700',
    marginBottom: 4,
  },
  subtitle: {
    color: palette.textSecondary,
    fontSize: 14,
    lineHeight: 20,
  },
  grid: {
    flexDirection: 'row',
    gap: 10,
    marginBottom: spacing.xl,
  },
  card: {
    alignItems: 'center',
    backgroundColor: palette.surface,
    borderColor: palette.border,
    borderRadius: radius.md,
    borderWidth: 1,
    flex: 1,
    minHeight: 164,
    justifyContent: 'center',
    paddingHorizontal: 14,
    paddingVertical: spacing.md,
  },
  cardSelected: {
    borderColor: palette.borderStrong,
    backgroundColor: palette.surfaceRaised,
  },
  focused: {
    borderColor: '#8ec2ff',
    backgroundColor: '#1e2a3c',
  },
  gamepadIcon: {
    marginBottom: 14,
  },
  cardTitle: {
    color: '#c8dcfa',
    fontSize: 13,
    fontWeight: '700',
    marginBottom: 4,
  },
  cardHint: {
    color: palette.textMuted,
    fontSize: 11,
    marginBottom: 12,
  },
  cardEmpty: {
    color: palette.textMuted,
    fontSize: 12,
  },
  cardDevice: {
    color: palette.textPrimary,
    fontSize: 12,
    fontWeight: '600',
    maxWidth: '100%',
    textAlign: 'center',
  },
  settingRow: {
    alignItems: 'center',
    backgroundColor: palette.surface,
    borderColor: palette.border,
    borderRadius: radius.md,
    borderWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: spacing.md,
    minHeight: 74,
    paddingHorizontal: spacing.lg,
  },
  settingDisabled: {
    opacity: 0.62,
  },
  settingText: {
    flex: 1,
    paddingRight: 16,
  },
  settingTitle: {
    color: palette.textPrimary,
    fontSize: 14,
    fontWeight: '800',
    marginBottom: 4,
  },
  settingDescription: {
    color: palette.textSecondary,
    fontSize: 12,
    lineHeight: 17,
  },
  button: {
    alignItems: 'center',
    borderColor: palette.borderStrong,
    borderRadius: radius.sm,
    borderWidth: 1,
    justifyContent: 'center',
    marginBottom: 16,
    marginTop: spacing.lg,
    minHeight: 52,
    paddingVertical: 12,
  },
  buttonStart: {
    backgroundColor: '#11486a',
  },
  buttonStop: {
    backgroundColor: '#56232b',
    borderColor: '#8f3f4a',
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  buttonPressed: {
    opacity: 0.82,
  },
  buttonText: {
    color: palette.textPrimary,
    fontSize: 15,
    fontWeight: '800',
    letterSpacing: 0.2,
  },
  message: {
    color: palette.textSecondary,
    fontSize: 14,
    lineHeight: 20,
  },
  hint: {
    color: palette.textMuted,
    fontSize: 12,
    lineHeight: 18,
    marginTop: 6,
  },
  overlay: {
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.78)',
    flex: 1,
    justifyContent: 'center',
    padding: 22,
  },
  modal: {
    backgroundColor: '#1a2230',
    borderColor: palette.border,
    borderRadius: radius.md,
    borderWidth: 1,
    maxHeight: 280,
    paddingHorizontal: 24,
    paddingVertical: 20,
    width: '100%',
    maxWidth: 290,
  },
  modalTitle: {
    color: palette.textPrimary,
    fontSize: 15,
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
    backgroundColor: '#253246',
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
    borderColor: palette.accent,
  },
  radioDot: {
    backgroundColor: palette.accent,
    borderRadius: 5,
    height: 10,
    width: 10,
  },
  optionText: {
    borderBottomColor: '#33445f',
    borderBottomWidth: 1,
    flex: 1,
    paddingBottom: 10,
    paddingTop: 10,
  },
  optionName: {
    color: palette.textPrimary,
    fontSize: 14,
    fontWeight: '700',
  },
  emptyModal: {
    color: palette.textSecondary,
    fontSize: 13,
    lineHeight: 18,
    paddingVertical: 12,
  },
});
