import React, { useState, useEffect, createContext, useCallback } from "react";
import ReactDOM from "react-dom/client";
import { RouterProvider } from "react-router";
import { router } from "./routes";
import { LoadingScreen } from "./components/LoadingScreen";
import "../styles/index.css";

import { WelcomeScreen } from "./components/android/WelcomeScreen";
import { AndroidOnboarding } from "./components/android/AndroidOnboarding";
import { androidBridge, AppState, GlobalContextType } from "./bridge";

export const AppStateContext = createContext<GlobalContextType | null>(null);

function Main() {
  const [appStateRaw, setAppStateRaw] = useState<AppState | null>(null);
  const [step, setStep] = useState<"loading" | "welcome" | "tutorial" | "app">("loading");

  const setAppState = useCallback((newState: Partial<AppState> | ((prev: AppState | null) => AppState | null)) => {
    setAppStateRaw(prev => {
      const computedNext = typeof newState === 'function' ? newState(prev) : { ...(prev || {} as AppState), ...newState };
      if (!computedNext) return null;
      
      const s = computedNext.storage;
      if (s) {
        if (s.totalBytes <= 0 || s.freeBytes < 0 || s.freeBytes > s.totalBytes) {
          console.error("[API_DEBUG] Invalid storage payload", s);
          if (prev && prev.storage && prev.storage.totalBytes > 0 && prev.storage.freeBytes >= 0 && prev.storage.freeBytes <= prev.storage.totalBytes) {
             computedNext.storage = prev.storage;
          } else {
             computedNext.storage = { totalBytes: 1, freeBytes: 1, usedBytes: 0 };
          }
        }
      }
      return computedNext as AppState;
    });
  }, []);

  const refreshStorage = useCallback(async () => {
     if (!window.Android || !appStateRaw?.node?.isRunning) return;
     try {
       const pwd = new URLSearchParams(window.location.hash.split('?')[1]).get('pwd') || appStateRaw?.node.shareCode || '';
       const res = await fetch(`http://127.0.0.1:8080/api/storage?t=${Date.now()}`, { headers: { Authorization: `Bearer ${pwd}` }, cache: 'no-store' });
       if (res.ok) {
          const data = await res.json();
          setAppState(prev => prev ? { ...prev, storage: { totalBytes: data.total || data.totalBytes, freeBytes: data.free || data.freeBytes, usedBytes: data.used || data.usedBytes } } : prev);
       }
     } catch (e) { console.error("[API_DEBUG] Storage fetch failed", e); }
  }, [appStateRaw?.node.shareCode, appStateRaw?.node.isRunning, setAppState]);

  const refreshNodeStatus = useCallback(async () => {
     if (!window.Android || !appStateRaw?.node?.isRunning) return;
     try {
       const pwd = new URLSearchParams(window.location.hash.split('?')[1]).get('pwd') || appStateRaw?.node.shareCode || '';
       const res = await fetch(`http://127.0.0.1:8080/api/status?t=${Date.now()}`, { headers: { Authorization: `Bearer ${pwd}` }, cache: 'no-store' });
       const isOnline = res.ok;
       setAppState(prev => prev ? { ...prev, node: { ...prev.node, isRunning: isOnline } } : prev);
     } catch {
       setAppState(prev => prev ? { ...prev, node: { ...prev.node, isRunning: false } } : prev);
     }
  }, [appStateRaw?.node.shareCode, appStateRaw?.node.isRunning, setAppState]);

  const refreshFiles = useCallback(async (path: string) => {
     if (!window.Android || !appStateRaw?.node?.isRunning) return;
     try {
       const pwd = new URLSearchParams(window.location.hash.split('?')[1]).get('pwd') || appStateRaw?.node.shareCode || '';
       const res = await fetch(`http://127.0.0.1:8080/api/files?path=${encodeURIComponent(path)}&t=${Date.now()}`, { 
         headers: { Authorization: `Bearer ${pwd}`, 'Cache-Control': 'no-store' } 
       });
       if (res.ok) {
         const items = await res.json();
         console.log("[JS_DEBUG] Network returned items length: " + items.length);
         setAppState(prev => prev ? { ...prev, files: { currentPath: path, items } } : prev);
       } else {
         console.log("[JS_DEBUG] Network returned NOT OK: " + res.status);
       }
     } catch (e: any) {
       console.error("[JS_DEBUG] Files fetch failed", e.message || e);
     }
  }, [appStateRaw?.node.shareCode, setAppState]);

  useEffect(() => {
    if (!window.Android) {
      if (!window.location.hash.startsWith("#/console") && !window.location.hash.startsWith("#/onboarding")) {
        window.location.hash = "#/console";
      }
      return;
    }

    const loadInitialState = async () => {
      const stateStr = await androidBridge.getInitialState();
      if (stateStr) {
         const parsed = JSON.parse(stateStr);
         setAppState({
            node: parsed.node || { isRunning: false, tunnelConnected: false, folderName: null, shareCode: '', relayBaseUrl: '' },
            storage: parsed.storage || { totalBytes: 1, freeBytes: 1, usedBytes: 0 },
            files: { currentPath: '', items: [] }
         });
      }
    };
    loadInitialState();
  }, [setAppState]);

  useEffect(() => {
    if (!window.Android) return;
    const interval = setInterval(() => {
      refreshNodeStatus();
      refreshStorage();
    }, 5000);
    return () => clearInterval(interval);
  }, [refreshNodeStatus, refreshStorage]);

  useEffect(() => {
    window.updateWebState = (stateJson: string) => {
      try {
        const parsed = JSON.parse(stateJson);
        setAppState(prev => {
           if (!prev) return prev;
           return {
              ...prev,
              node: { ...prev.node, ...(parsed.node || {}) },
              storage: parsed.storage ? { ...prev.storage, ...parsed.storage } : prev.storage
           };
        });
      } catch (e) {
        console.error("[API_DEBUG] Native state update failed", e);
      }
    };
  }, [setAppState]);

  if (step === "loading") {
    return <LoadingScreen onComplete={() => {
      if (!window.Android) {
        setStep("app");
      } else {
        const hasSeenTutorial = localStorage.getItem("hasSeenTutorial");
        setStep(hasSeenTutorial ? "app" : "welcome");
      }
    }} />;
  }

  if (step === "welcome") {
    return (
      <WelcomeScreen 
        onStart={() => setStep("tutorial")} 
        onSkip={() => {
          localStorage.setItem("hasSeenTutorial", "true");
          setStep("app");
        }} 
      />
    );
  }

  if (step === "tutorial") {
    return (
      <AndroidOnboarding 
        onComplete={() => {
          localStorage.setItem("hasSeenTutorial", "true");
          setStep("app");
        }} 
      />
    );
  }

  const contextValue: GlobalContextType = {
    state: appStateRaw,
    refreshStorage,
    refreshNodeStatus,
    refreshFiles
  };

  return (
    <AppStateContext.Provider value={contextValue}>
      <RouterProvider router={router} />
    </AppStateContext.Provider>
  );
}

const rootElement = document.getElementById("root");
if (rootElement) {
  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
      <Main />
    </React.StrictMode>
  );
}