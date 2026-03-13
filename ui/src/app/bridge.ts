export interface AppState {
  folderName: string | null;
  shareCode: string;
  relayBaseUrl: string;
  isNodeRunning: boolean;
  storageUsed: number;
  storageTotal: number;
  usagePercent: number;
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
    };
    updateWebState?: (stateJson: string) => void;
  }
}

export const androidBridge = {
  getInitialState: (): AppState | null => {
    if (window.Android) {
      try {
        return JSON.parse(window.Android.getInitialState()) as AppState;
      } catch (e) {
        console.error("Failed to parse initial state", e);
      }
    }
    return null;
  },
  selectFolder: () => window.Android?.selectFolder(),
  toggleNode: () => window.Android?.toggleNode(),
  shareInvite: () => window.Android?.shareInvite(),
  copyToClipboard: (text: string, toast: string) => 
    window.Android?.copyToClipboard(text, toast),
  updateRelayBaseUrl: (url: string) => window.Android?.updateRelayBaseUrl(url),
  
  isAvailable: () => typeof window.Android !== 'undefined'
};
