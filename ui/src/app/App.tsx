import React, { useState, useEffect, createContext } from "react";
import ReactDOM from "react-dom/client";
import { RouterProvider } from "react-router";
import { router } from "./routes";
import { LoadingScreen } from "./components/LoadingScreen";
import "../styles/index.css";

import { WelcomeScreen } from "./components/android/WelcomeScreen";
import { AndroidOnboarding } from "./components/android/AndroidOnboarding";
import { androidBridge, AppState } from "./bridge";

export const AppStateContext = createContext<AppState | null>(null);

function Main() {
  const [appState, setAppState] = useState<AppState | null>(null);
  const [step, setStep] = useState<"loading" | "welcome" | "tutorial" | "app">("loading");

  useEffect(() => {
    if (!window.Android) {
      if (!window.location.hash.startsWith("#/console") && !window.location.hash.startsWith("#/onboarding")) {
        window.location.hash = "#/console";
      }
      return;
    }

    const loadInitialState = async () => {
      const state = await androidBridge.getInitialState();
      if (state) setAppState(state);
    };
    loadInitialState();
  }, []);

  useEffect(() => {
    window.addEventListener('error', (event) => {
      const debugInfo = document.getElementById('debug-info');
      if (debugInfo) {
        debugInfo.innerHTML = "JS ERROR: " + event.message;
        debugInfo.style.background = 'red';
      }
    });

    window.updateWebState = (stateJson: string) => {
      try {
        const newState = JSON.parse(stateJson);
        setAppState(newState);
      } catch (e) {
        console.error("Native state update failed", e);
      }
    };
  }, []);

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

  return (
    <AppStateContext.Provider value={appState}>
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