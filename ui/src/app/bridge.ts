import { Capacitor } from '@capacitor/core';

export interface FileItem {
  id: string;
  name: string;
  isDirectory: boolean;
  size: number;
  lastModified: number;
}

export interface ConnectedPeer {
  browserId: string;
  connectedAt: number;
  displayName: string;
  role: string;
}

export interface AppState {
  node: {
    isRunning: boolean;
    tunnelConnected: boolean;
    tunnelStatus?: string;
    folderName: string | null;
    shareCode: string;
    relayBaseUrl: string;
    lanUrl?: string;
    health?: {
      cpu: string;
      memory: string;
      ping: string;
      io: string;
    };
    connectedPeers?: ConnectedPeer[];
    guestAccessEnabled?: boolean;
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
      shareLink?(text: string): void;
      resetNodePassword?(): void;
      toggleGuestAccess?(enabled: boolean): void;
      changePeerRole?(browserId: string, role: string): void;
    };
    updateWebState?: (stateJson: string) => void;
    onPeerEvent?: (eventJson: string) => void;
    onActivityEvent?: (eventJson: string) => void;
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
  },

  shareLink: (text: string) => {
    if (window.Android?.shareLink) {
      window.Android.shareLink(text);
    } else {
      // Fallback for non-Android: copy to clipboard
      navigator.clipboard?.writeText(text);
    }
  },

  resetNodePassword: () => {
    if (window.Android?.resetNodePassword) {
      window.Android.resetNodePassword();
    }
  },

  toggleGuestAccess: (enabled: boolean) => {
    if (window.Android?.toggleGuestAccess) {
      window.Android.toggleGuestAccess(enabled);
    }
  },

  changePeerRole: (browserId: string, role: string) => {
    if (window.Android?.changePeerRole) {
      window.Android.changePeerRole(browserId, role);
    }
  }
};
