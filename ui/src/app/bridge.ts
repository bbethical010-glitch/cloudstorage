import { Capacitor } from '@capacitor/core';

export interface FileItem {
  id: string;
  name: string;
  isDirectory: boolean;
  size: number;
  lastModified: number;
}

export interface AppState {
  node: {
    isRunning: boolean;
    tunnelConnected: boolean;
    tunnelStatus?: string;
    folderName: string | null;
    shareCode: string;
    relayBaseUrl: string;
    health?: {
      cpu: string;
      memory: string;
      ping: string;
      io: string;
    };
  };
  storage: {
    totalBytes: number;
    freeBytes: number;
    usedBytes: number;
  };
  files: {
    currentPath: string;
    items: FileItem[];
  };
}

export interface GlobalContextType {
  state: AppState | null;
  refreshStorage: () => Promise<void>;
  refreshNodeStatus: () => Promise<void>;
  refreshFiles: (path: string) => Promise<void>;
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
      showNotification(title: string, message: string): void;
      scanQRCode?(): void;
    };
    updateWebState?: (stateJson: string) => void;
  }
}

export const androidBridge = {
  getInitialState: async (): Promise<string | null> => {
    if (window.Android) {
      try {
        return window.Android.getInitialState();
      } catch (e) {
        console.error("[API_DEBUG] Failed to fetch Android initial state", e);
      }
    }
    return null;
  },

  selectFolder: () => {
    if (window.Android) window.Android.selectFolder();
  },

  toggleNode: () => {
    if (window.Android) window.Android.toggleNode();
  },

  shareInvite: () => {
    if (window.Android) window.Android.shareInvite();
  },

  copyToClipboard: (text: string, toastMsg: string) => {
    if (window.Android) window.Android.copyToClipboard(text, toastMsg);
  },

  updateRelayBaseUrl: (url: string) => {
    if (window.Android) window.Android.updateRelayBaseUrl(url);
  },

  scanDocument: () => {
    if (window.Android?.scanDocument) window.Android.scanDocument();
  },
  
  showNotification: (title: string, message: string) => {
    if (window.Android?.showNotification) window.Android.showNotification(title, message);
  },

  isAvailable: () => {
    return Capacitor.isNativePlatform();
  }
};
