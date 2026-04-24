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

export interface ActivityEvent {
  action: string;
  fileName: string;
  actor: string;
  timestamp: number;
  details: string;
}

export const AppStateContext = createContext<GlobalContextType | null>(null);
export const ActivityFeedContext = createContext<ActivityEvent[]>([]);

const LOCAL_NODE_ORIGIN = "http://127.0.0.1:8080";

function localNodeApi(endpoint: string) {
  // Android loads the bundle from https://app.local.cloud. Calling the local
  // node through the same origin lets MainActivity proxy GET requests and keeps
  // WebView out of CORS/mixed-content territory.
  return window.Android ? endpoint : `${LOCAL_NODE_ORIGIN}${endpoint}`;
}

function Main() {
  const [appStateRaw, setAppStateRaw] = useState<AppState | null>(null);
  const [step, setStep] = useState<"loading" | "welcome" | "tutorial" | "auth" | "app">("loading");
  const [activityFeed, setActivityFeed] = useState<ActivityEvent[]>([]);

  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [authMode, setAuthMode] = useState<'login' | 'signup' | 'none'>('none');
  const [authPassword, setAuthPassword] = useState('');
  const [authError, setAuthError] = useState('');
  const [authChecked, setAuthChecked] = useState(false);

  const setAppState = useCallback((newState: Partial<AppState> | ((prev: AppState | null) => AppState | null)) => {
    setAppStateRaw(prev => {
      const computedNext = typeof newState === 'function' ? newState(prev) : { ...(prev || {} as AppState), ...newState };
      if (!computedNext) return null;
      
      const s = computedNext.storage;
      if (s) {
        // Allow totalBytes === 0 for initial state or unselected folders
        if (s.totalBytes < 0 || s.freeBytes < 0 || s.freeBytes > s.totalBytes) {
          console.error("[API_DEBUG] Invalid storage payload", JSON.stringify(s));
          if (prev && prev.storage && prev.storage.totalBytes >= 0 && prev.storage.freeBytes >= 0 && prev.storage.freeBytes <= prev.storage.totalBytes) {
             computedNext.storage = prev.storage;
          } else {
             computedNext.storage = { totalBytes: 0, freeBytes: 0, usedBytes: 0 };
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
       const res = await fetch(localNodeApi(`/api/storage?t=${Date.now()}`), { headers: { Authorization: `Bearer ${pwd}` }, cache: 'no-store' });
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
       const res = await fetch(localNodeApi(`/api/status?t=${Date.now()}`), { headers: { Authorization: `Bearer ${pwd}` }, cache: 'no-store' });
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
       const res = await fetch(localNodeApi(`/api/files?path=${encodeURIComponent(path)}&t=${Date.now()}`), { 
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
  }, [appStateRaw?.node?.isRunning, setAppState]);

  useEffect(() => {
    // Web Console (non-Android): check auth immediately
    if (!window.Android) {
      // Determine the API base URL from the current page URL
      const checkRemoteAuth = async () => {
        try {
          // Check if we have a stored session token
          // Get nodeId from URL hash if present
          const nodeId = new URLSearchParams(window.location.hash.split('?')[1]).get('nodeId') || '';
          
          // Check auth status from the node
          const authRes = await fetch(`/api/auth/status?nodeId=${encodeURIComponent(nodeId)}&t=${Date.now()}`);
          if (authRes.ok) {
            const { hasAccount } = await authRes.json();
            
            if (hasAccount && storedToken) {
              // Verify the stored token is still valid
              const verify = await fetch(`/api/storage`, { 
                headers: { Authorization: `Bearer ${storedToken}` } 
              });
              if (verify.ok) {
                setIsAuthenticated(true);
                setAuthMode('none');
                setAuthChecked(true);
                setStep('app');
                return;
              }
            }
            
            // Need authentication
            setAuthMode(hasAccount ? 'login' : 'signup');
            setAuthChecked(true);
            setStep('auth');
          } else {
            // Auth endpoint not available, show app anyway
            setAuthChecked(true);
            setStep('app');
          }
        } catch (e) {
          console.error('[AUTH] Remote auth check failed', e);
          setAuthChecked(true);
          setStep('auth');
          setAuthMode('login');
        }
      };
      checkRemoteAuth();
      return;
    }

    const loadInitialState = async () => {
      let resolved = false;
      
      // Safety timeout in case bridge hangs
      const timeout = setTimeout(() => {
        if (!resolved) {
          console.warn("[API_DEBUG] Bridge load timed out. Falling back to defaults.");
          setAppState({
            node: { isRunning: false, tunnelConnected: false, folderName: null, shareCode: '', relayBaseUrl: '' },
            storage: { totalBytes: 0, freeBytes: 0, usedBytes: 0 },
            files: { currentPath: '', items: [] }
          });
        }
      }, 2000);

      try {
        const stateStr = await androidBridge.getInitialState();
        resolved = true;
        clearTimeout(timeout);
        
        if (stateStr) {
           const parsed = JSON.parse(stateStr);
           setAppState({
              node: parsed.node || { isRunning: false, tunnelConnected: false, folderName: null, shareCode: '', relayBaseUrl: '' },
              storage: parsed.storage || { totalBytes: 0, freeBytes: 0, usedBytes: 0 },
              files: { currentPath: '', items: [] }
           });
        } else {
           // Bridge returned empty, still need to set an app state to unblock UI
           setAppState({
              node: { isRunning: false, tunnelConnected: false, folderName: null, shareCode: '', relayBaseUrl: '' },
              storage: { totalBytes: 0, freeBytes: 0, usedBytes: 0 },
              files: { currentPath: '', items: [] }
           });
        }
      } catch (e) {
        resolved = true;
        clearTimeout(timeout);
        console.error("[API_DEBUG] Failed to load or parse initial state", e);
        setAppState({
          node: { isRunning: false, tunnelConnected: false, folderName: null, shareCode: '', relayBaseUrl: '' },
          storage: { totalBytes: 0, freeBytes: 0, usedBytes: 0 },
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

  useEffect(() => {
    // Only log transitions for API_DEBUG, but don't force transitions if we are already in the app
    console.log("[API_DEBUG] Current Step Transitions: ", step);
    
    // We only enforce "welcome" if the state is evaluated and we were somehow disconnected completely,
    // but we NEVER force out of 'app' or 'loading' automatically if the node is locally mounted.
    if (appStateRaw && step === "auth" && appStateRaw.node.folderName) {
      // If auth passes and folder is there, ensure we go to app eventually.
      // Handled by Auth Guard below.
    }
  }, [appStateRaw, step]);

  // Auth Guard — for Android WebView only
  useEffect(() => {
    if (!window.Android || !appStateRaw?.node?.isRunning) return;
    const checkAuth = async () => {
      try {
        const pwd = new URLSearchParams(window.location.hash.split('?')[1]).get('pwd') || appStateRaw?.node.shareCode || '';
        const token = localStorage.getItem('cloud_storage_android_token') || pwd;
        
        const nodeId = appStateRaw?.node.shareCode || '';
        const authStat = await fetch(localNodeApi(`/api/auth/status?nodeId=${encodeURIComponent(nodeId)}&t=${Date.now()}`));
        if (authStat.ok) {
           const { hasAccount } = await authStat.json();
           
           if (token || window.Android) {
                // On Android, we trust the local bridge for first-time setup and subsequent access
                setIsAuthenticated(true);
                setAuthMode('none');
                if (step === 'auth') {
                   const hasTut = localStorage.getItem("hasSeenTutorial");
                   setStep(hasTut ? "app" : "welcome");
                }
                return;
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

    // Peer lifecycle event handler — triggered by Android native bridge
    window.onPeerEvent = (eventJson: string) => {
      try {
        const event = JSON.parse(eventJson);
        if (event.type === 'joined') {
          toast.info(`${event.displayName} connected`, {
            description: 'New peer joined your node',
            duration: 4000,
          });
        } else if (event.type === 'left') {
          toast(`${event.displayName} disconnected`, {
            duration: 3000,
          });
        }
      } catch (e) {
        console.error("[PEER_EVENT] Failed to parse peer event", e);
      }
    };

    // Activity event handler — triggered by Android native bridge
    window.onActivityEvent = (eventJson: string) => {
      try {
        const event = JSON.parse(eventJson) as ActivityEvent;
        setActivityFeed(prev => [event, ...prev].slice(0, 50));

        // Show toast for activity events (not from Admin to avoid self-notifications)
        const actionLabels: Record<string, string> = {
          upload: '📤 uploaded',
          delete: '🗑️ deleted',
          rename: '✏️ renamed',
          create_folder: '📁 created folder',
          bulk_delete: '🗑️ bulk deleted',
          bulk_move: '📦 bulk moved',
          role_change: '🔑 role changed for',
        };
        const label = actionLabels[event.action] || event.action;
        const detail = event.details ? ` (${event.details})` : '';
        toast.info(`${event.actor} ${label} ${event.fileName}${detail}`, {
          duration: 4000,
        });
      } catch (e) {
        console.error("[ACTIVITY_EVENT] Failed to parse activity event", e);
      }
    };

    return () => {
      window.onPeerEvent = undefined;
      window.onActivityEvent = undefined;
    };
  }, [setAppState]);

  const performAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError('');
    try {
      const isRemote = !window.Android;
      const baseUrl = isRemote ? '' : 'http://127.0.0.1:8080';
      const endpoint = authMode === 'signup' ? '/api/auth/signup' : '/api/auth/login';
      const res = await fetch(`${baseUrl}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: authPassword })
      });
      const data = await res.json();
      if (res.ok && data.token) {
        if (isRemote) {
          sessionStorage.setItem('cloud_storage_session_token', data.token);
        } else {
          localStorage.setItem('cloud_storage_android_token', data.token);
        }
        setIsAuthenticated(true);
        setAuthMode('none');
        toast.success("Identity Secured");
        if (isRemote) {
          setStep('app');
        } else {
          const hasTut = localStorage.getItem("hasSeenTutorial");
          setStep(hasTut ? "app" : "welcome");
        }
      } else {
        setAuthError(data.error || "Authentication failed");
      }
    } catch {
      setAuthError("Network error. Is the node online?");
    }
  };

  const contextValue: GlobalContextType = {
    state: appStateRaw,
    refreshStorage,
    refreshNodeStatus,
    refreshFiles
  };

  const isWebConsole = !window.Android;

  return (
    <AppStateContext.Provider value={contextValue}>
    <ActivityFeedContext.Provider value={activityFeed}>
      <div className={isWebConsole
        ? "w-full h-dvh min-h-dvh overflow-hidden bg-[#08090E]"
        : "w-full h-dvh min-h-dvh overflow-y-auto overflow-x-hidden overscroll-y-contain bg-[#0B1220] shadow-2xl shadow-blue-900/5"
      }>
        {step === "loading" && (
          <LoadingScreen onComplete={() => {
            if (!window.Android) {
              setStep("app");
            } else {
              if (authMode !== 'none') return;
              const hasSeenTutorial = localStorage.getItem("hasSeenTutorial");
              setStep(hasSeenTutorial ? "app" : "welcome");
            }
          }} />
        )}

        {step === "auth" && (
          <div className="h-full w-full bg-[#0B1220] flex flex-col items-center justify-center p-6 text-[#E5E7EB] relative overflow-hidden">
            <div className="absolute top-[-20%] left-[-10%] w-[50%] h-[50%] bg-[#2563EB]/20 blur-[120px] rounded-full pointer-events-none" />
            <div className="absolute bottom-[-20%] right-[-10%] w-[50%] h-[50%] bg-[#A855F7]/20 blur-[120px] rounded-full pointer-events-none" />
            
            <div className="z-10 w-full max-w-sm">
              <Card className="bg-[#111827]/80 backdrop-blur-xl border-[#1F2937] p-8 shadow-2xl rounded-3xl mx-auto">
                <div className="flex justify-center mb-6">
                  <div className="w-16 h-16 bg-[#2563EB] rounded-2xl flex items-center justify-center">
                    <Cloud className="w-8 h-8 text-white" />
                  </div>
                </div>
                <h2 className="text-2xl font-bold text-center text-white mb-2">
                  {authMode === 'signup' ? 'Secure Your Node' : 'Node Locked'}
                </h2>
                <p className="text-xs text-[#9CA3AF] text-center mb-6">
                  {authMode === 'signup' 
                    ? 'Create a password to protect your storage node.' 
                    : 'Enter your node password to continue.'}
                </p>
                <form onSubmit={performAuth} className="space-y-4">
                  <Input type="password" required placeholder="••••••••" value={authPassword} onChange={e => setAuthPassword(e.target.value)}
                    className="bg-[#0B1220] border-[#374151] text-white h-12 rounded-xl" />
                  {authError && <div className="text-red-400 text-xs font-medium text-center">{authError}</div>}
                  <Button type="submit" className="w-full h-12 bg-[#2563EB] hover:bg-[#1d4ed8] text-white font-bold rounded-xl">
                    {authMode === 'signup' ? 'Set Password' : 'Unlock Node'}
                  </Button>
                </form>
              </Card>
            </div>
          </div>
        )}

        {step === "welcome" && (
          <WelcomeScreen 
            onStart={() => setStep("tutorial")} 
            onSkip={() => {
              localStorage.setItem("hasSeenTutorial", "true");
              setStep("app");
            }} 
          />
        )}

        {step === "tutorial" && (
          <AndroidOnboarding 
            onComplete={() => {
              localStorage.setItem("hasSeenTutorial", "true");
              setStep("app");
            }} 
          />
        )}

        {step === "app" && <RouterProvider router={router} />}
      </div>
    </ActivityFeedContext.Provider>
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
