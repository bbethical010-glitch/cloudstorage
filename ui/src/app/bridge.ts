import { Capacitor } from '@capacitor/core';

export interface AppState {
  folderName: string | null;
  shareCode: string;
  relayBaseUrl: string;
  isNodeRunning: boolean;
  tunnelStatus: 'Offline' | 'Connecting' | 'Connected' | 'Error';
  storageUsed: number;
  storageTotal: number;
  usagePercent: number;
  health?: {
    cpu: string;
    memory: string;
    ping: string;
    io: string;
  };
}

declare global {
  interface Window {
    Android?: {
      getInitialState(): string;
      selectFolder(): void;
      toggleNode(): void;
      shareInvite(): void;
      copyToClipboard(text: string, toast: string): void;
      updateRelayBaseUrl(url: string): void;
      scanDocument(): void;
    };
    updateWebState?: (stateJson: string) => void;
  }
}

// Custom hook-like bridge that delegates to Android or Capacitor (iOS)
export const androidBridge = {
  getInitialState: async (): Promise<AppState | null> => {
    if (window.Android) {
      try {
        return JSON.parse(window.Android.getInitialState()) as AppState;
      } catch (e) {
        console.error("Failed to parse Android initial state", e);
      }
    } else if (Capacitor.getPlatform() === 'ios') {
       // On iOS we'll use a Capacitor Plugin
       // Placeholder for now, implemented via Swift bridge
       return null; 
    }
    return null;
  },

  selectFolder: () => {
    if (window.Android) {
      window.Android.selectFolder();
    } else {
      // iOS / Capacitor implementation
      (window as any).Capacitor?.Plugins?.StoragePlugin?.selectFolder();
    }
  },

  toggleNode: () => {
    if (window.Android) {
      window.Android.toggleNode();
    } else {
      (window as any).Capacitor?.Plugins?.StoragePlugin?.toggleNode();
    }
  },

  shareInvite: () => {
    if (window.Android) {
      window.Android.shareInvite();
    } else {
      (window as any).Capacitor?.Plugins?.StoragePlugin?.shareInvite();
    }
  },

  copyToClipboard: (text: string, toastMsg: string) => {
    if (window.Android) {
      window.Android.copyToClipboard(text, toastMsg);
    } else {
      // iOS / Capacitor placeholder
      (window as any).Capacitor?.Plugins?.StoragePlugin?.copyToClipboard({ text, toast: toastMsg });
    }
  },

  updateRelayBaseUrl: (url: string) => {
    if (window.Android) {
      window.Android.updateRelayBaseUrl(url);
    } else {
      (window as any).Capacitor?.Plugins?.StoragePlugin?.updateRelayBaseUrl({ url });
    }
  },

  scanDocument: () => {
    if (window.Android?.scanDocument) {
      window.Android.scanDocument();
    }
  },
  
  isAvailable: () => {
    return Capacitor.isNativePlatform();
  }
};
