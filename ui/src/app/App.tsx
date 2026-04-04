import React, { useState, useEffect, createContext, useCallback } from "react";
import ReactDOM from "react-dom/client";
import { RouterProvider } from "react-router";
import { router } from "./routes";
import { LoadingScreen } from "./components/LoadingScreen";
import "../styles/index.css";
import { Cloud } from "lucide-react";
import { Card } from "./components/ui/card";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { motion } from "motion/react";
import { toast } from "sonner";

import { WelcomeScreen } from "./components/android/WelcomeScreen";
import { AndroidOnboarding } from "./components/android/AndroidOnboarding";
import { androidBridge, AppState, GlobalContextType } from "./bridge";

export const AppStateContext = createContext<GlobalContextType | null>(null);

function Main() {
  const [appStateRaw, setAppStateRaw] = useState<AppState | null>(null);
  const [step, setStep] = useState<"loading" | "welcome" | "tutorial" | "auth" | "app">("loading");

  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [authMode, setAuthMode] = useState<'login' | 'signup' | 'none'>('none');
  const [authEmail, setAuthEmail] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authError, setAuthError] = useState('');

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
       const token = localStorage.getItem('cloud_storage_android_token') || '';
       const res = await fetch(`http://127.0.0.1:8080/api/files?path=${encodeURIComponent(path)}&t=${Date.now()}`, { 
         headers: { Authorization: `Bearer ${token}`, 'Cache-Control': 'no-store' } 
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
  }, [setAppState]);

  useEffect(() => {
    if (!window.Android) {
      if (!window.location.hash.startsWith("#/console") && !window.location.hash.startsWith("#/onboarding")) {
        window.location.hash = "#/console";
      }
      return;
    }

    const loadInitialState = async () => {
      try {
        const stateStr = await androidBridge.getInitialState();
        if (stateStr) {
           const parsed = JSON.parse(stateStr);
           setAppState({
              node: parsed.node || { isRunning: false, tunnelConnected: false, folderName: null, shareCode: '', relayBaseUrl: '' },
              storage: parsed.storage || { totalBytes: 1, freeBytes: 1, usedBytes: 0 },
              files: { currentPath: '', items: [] }
           });
        }
      } catch (e) {
        console.error("[API_DEBUG] Failed to load or parse initial state", e);
        // Ensure app still loads even if bridge fails
        setAppState({
          node: { isRunning: false, tunnelConnected: false, folderName: null, shareCode: '', relayBaseUrl: '' },
          storage: { totalBytes: 1, freeBytes: 1, usedBytes: 0 },
          files: { currentPath: '', items: [] }
        });
      }
    };
    loadInitialState();
    
    // Apply Startup Theme
    const savedSettings = localStorage.getItem('appSettings');
    if (savedSettings) {
      try {
        const parsedTheme = JSON.parse(savedSettings).theme;
        if (parsedTheme === 'Light') {
          document.documentElement.classList.add('light');
        }
      } catch(e) {}
    }
  }, [setAppState]);

  // Auth Guard
  useEffect(() => {
    if (!window.Android || !appStateRaw?.node?.isRunning) return;
    const checkAuth = async () => {
      try {
        const pwd = new URLSearchParams(window.location.hash.split('?')[1]).get('pwd') || appStateRaw?.node.shareCode || '';
        const token = localStorage.getItem('cloud_storage_android_token') || pwd;
        
        const authStat = await fetch(`http://127.0.0.1:8080/api/auth/status?t=${Date.now()}`);
        if (authStat.ok) {
           const { hasAccount } = await authStat.json();
           
           if (token) {
               const verify = await fetch(`http://127.0.0.1:8080/api/storage`, { headers: { Authorization: `Bearer ${token}` }});
               if (verify.ok) {
                   setIsAuthenticated(true);
                   setAuthMode('none');
                   if (step === 'loading' || step === 'auth') {
                      const hasTut = localStorage.getItem("hasSeenTutorial");
                      setStep(hasTut ? "app" : "welcome");
                   }
                   return;
               }
           }
           
           setIsAuthenticated(false);
           setAuthMode(hasAccount ? 'login' : 'signup');
           setStep('auth');
        }
      } catch (e) {}
    };
    checkAuth();
  }, [appStateRaw?.node?.isRunning, step]);

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

  const performAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError('');
    try {
      const endpoint = authMode === 'signup' ? '/api/auth/signup' : '/api/auth/login';
      const res = await fetch(`http://127.0.0.1:8080${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: authEmail, password: authPassword })
      });
      const data = await res.json();
      if (res.ok && data.token) {
        localStorage.setItem('cloud_storage_android_token', data.token);
        setIsAuthenticated(true);
        setAuthMode('none');
        toast.success("Identity Secured");
        const hasTut = localStorage.getItem("hasSeenTutorial");
        setStep(hasTut ? "app" : "welcome");
      } else {
        setAuthError(data.error || "Authentication failed");
      }
    } catch {
      setAuthError("Network error. Is the node online?");
    }
  };

  if (step === "loading") {
    return <LoadingScreen onComplete={() => {
      if (!window.Android) {
        setStep("app");
      } else {
        if (authMode !== 'none') return;
        const hasSeenTutorial = localStorage.getItem("hasSeenTutorial");
        setStep(hasSeenTutorial ? "app" : "welcome");
      }
    }} />;
  }

  if (step === "auth") {
    return (
      <div className="h-[100dvh] w-full bg-[#0B1220] flex flex-col items-center justify-center p-6 text-[#E5E7EB] relative overflow-hidden">
        <div className="absolute top-[-20%] left-[-10%] w-[50%] h-[50%] bg-[#2563EB]/20 blur-[120px] rounded-full pointer-events-none" />
        <div className="absolute bottom-[-20%] right-[-10%] w-[50%] h-[50%] bg-[#A855F7]/20 blur-[120px] rounded-full pointer-events-none" />
        
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="z-10 w-full max-w-sm">
          <Card className="bg-[#111827]/80 backdrop-blur-xl border-[#1F2937] p-8 shadow-2xl rounded-3xl mx-auto">
            <div className="flex justify-center mb-6">
              <div className="w-16 h-16 bg-gradient-to-br from-[#2563EB] to-[#A855F7] rounded-2xl flex items-center justify-center shadow-lg shadow-blue-500/20">
                <Cloud className="w-8 h-8 text-white" />
              </div>
            </div>
            <h2 className="text-2xl font-bold text-center text-white mb-2">
              {authMode === 'signup' ? 'Secure Local Node' : 'Node Locked'}
            </h2>
            <p className="text-center text-[#9CA3AF] mb-8 text-xs">
              {authMode === 'signup' ? 'Create an admin identity across the mesh.' : 'Authenticate to manage your local node.'}
            </p>

            <form onSubmit={performAuth} className="space-y-4">
              <div className="space-y-1">
                <Input type="email" required placeholder="admin@local.host" value={authEmail} onChange={e => setAuthEmail(e.target.value)}
                  className="bg-[#0B1220] border-[#374151] text-white h-12 rounded-xl" />
              </div>
              <div className="space-y-1">
                <Input type="password" required placeholder="••••••••" value={authPassword} onChange={e => setAuthPassword(e.target.value)}
                  className="bg-[#0B1220] border-[#374151] text-white h-12 rounded-xl" />
              </div>
              {authError && <div className="text-red-400 text-xs font-medium text-center bg-red-500/10 py-2 rounded-lg">{authError}</div>}
              <Button type="submit" className="w-full h-12 bg-[#2563EB] hover:bg-[#1d4ed8] text-white font-bold rounded-xl mt-4">
                {authMode === 'signup' ? 'Lock Core API' : 'Unlock Node Engine'}
              </Button>
            </form>
          </Card>
        </motion.div>
      </div>
    );
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

  const isWebConsole = !window.Android;

  return (
    <AppStateContext.Provider value={contextValue}>
      <div className={isWebConsole
        ? "w-full h-[100dvh] overflow-hidden bg-[#08090E]"
        : "w-full max-w-md mx-auto md:max-w-xl lg:max-w-2xl h-[100dvh] overflow-y-auto overflow-x-hidden overscroll-y-contain bg-[#0B1220] shadow-2xl shadow-blue-900/5"
      }>
        <RouterProvider router={router} />
      </div>
    </AppStateContext.Provider>
  );
}

// === Client-Side Hardening ===
window.addEventListener('unhandledrejection', (event) => {
  console.error("Resource or Promise failed to load:", event.reason);
  // Prevent the app from freezing entirely
  event.preventDefault();
});

window.addEventListener('error', (event) => {
  console.error("Global crash caught:", event.error);
});
// =============================

const rootElement = document.getElementById("root");
if (rootElement) {
  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
      <Main />
    </React.StrictMode>
  );
}
