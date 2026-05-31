import React from 'react';
import {
  ActivityIndicator,
  Modal,
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

type PressableFocusState = {
  focused?: boolean;
  pressed?: boolean;
  hovered?: boolean;
};

export default function App() {
  const {
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
  } = useMirrorController();

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

        <Text style={styles.subtitle}>Select a controller to configure</Text>

        <View style={styles.grid}>
          <DeviceCard
            title="Local Controller"
            device={localDevice}
            selected={activeSlot === 'local'}
            preferredFocus
            onPress={() => openDeviceModal('local')}
          />
          <DeviceCard
            title="External Controller"
            device={externalDevice}
            selected={activeSlot === 'external'}
            onPress={() => openDeviceModal('external')}
          />
        </View>

        <Pressable
          focusable
          disabled={enabled || busy}
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

        <Pressable
          focusable
          disabled={enabled || busy}
          onPress={() => toggleAutoRestart(!autoRestart)}
          style={({focused, pressed}: PressableFocusState) => [
            styles.settingRow,
            focused && styles.focused,
            pressed && styles.buttonPressed,
            (enabled || busy) && styles.settingDisabled,
          ]}>
          <View style={styles.settingText}>
            <Text style={styles.settingTitle}>Auto restart mirror</Text>
            <Text style={styles.settingDescription}>Restart when the mirror stops or controllers reconnect</Text>
          </View>
          <Switch
            disabled={enabled || busy}
            value={autoRestart}
            onValueChange={toggleAutoRestart}
            trackColor={{false: '#2b2d38', true: '#1585C3'}}
            thumbColor={autoRestart ? '#d7f1ff' : '#7e8494'}
          />
        </Pressable>

        <Pressable
          disabled={!canToggle || busy || loading}
          onPress={toggleMirror}
          style={({focused, pressed}: PressableFocusState) => [
            styles.button,
            enabled ? styles.buttonStop : styles.buttonStart,
            (!canToggle || busy || loading) && styles.buttonDisabled,
            focused && styles.focused,
            pressed && styles.buttonPressed,
          ]}>
          <Text style={styles.buttonText}>
            {busy ? 'Processing...' : enabled ? 'Stop Mirror' : 'Start Mirror'}
          </Text>
        </Pressable>

        <Text style={styles.message}>
          {loading ? 'Scanning controllers...' : restartWaiting ? 'Waiting for controllers to reconnect...' : message}
        </Text>
        {!loading && !enabled && !canStart && (
          <Text style={styles.hint}>Select different local and external controllers.</Text>
        )}
      </ScrollView>

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
        {device?.name ?? 'No device'}
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
                  </View>
                </Pressable>
              );
            })}
            {devices.length === 0 && <Text style={styles.emptyModal}>No controllers detected.</Text>}
          </ScrollView>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#0b0c0e',
  },
  scroller: {
    flex: 1,
  },
  container: {
    flexGrow: 1,
    paddingHorizontal: 22,
    paddingTop: 28,
    paddingBottom: 36,
  },
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 34,
  },
  titleRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
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
    backgroundColor: '#0f3850',
    color: '#58c7ff',
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
  settingRow: {
    alignItems: 'center',
    backgroundColor: '#1a1b22',
    borderColor: '#2c2f3b',
    borderRadius: 12,
    borderWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 24,
    minHeight: 66,
    paddingHorizontal: 16,
  },
  settingDisabled: {
    opacity: 0.62,
  },
  settingText: {
    flex: 1,
    paddingRight: 16,
  },
  settingTitle: {
    color: '#f3f6fb',
    fontSize: 14,
    fontWeight: '800',
    marginBottom: 5,
  },
  settingDescription: {
    color: '#74839a',
    fontSize: 12,
  },
  button: {
    alignItems: 'center',
    borderColor: '#4a4b51',
    borderRadius: 7,
    borderWidth: 1,
    justifyContent: 'center',
    marginBottom: 16,
    minHeight: 44,
    paddingVertical: 10,
  },
  buttonStart: {
    backgroundColor: 'transparent',
  },
  buttonStop: {
    backgroundColor: '#3d1618',
    borderColor: '#723033',
  },
  buttonDisabled: {
    backgroundColor: '#0b3f5f',
    borderColor: '#0f4e75',
    opacity: 0.68,
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
  emptyModal: {
    color: '#74839a',
    fontSize: 13,
    lineHeight: 18,
    paddingVertical: 12,
  },
});
