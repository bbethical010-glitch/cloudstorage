import { Outlet, Link, useLocation, useNavigate } from "react-router";
import { Smartphone, Monitor } from "lucide-react";
import { Button } from "./ui/button";
import { useEffect } from "react";
import { Toaster } from "sonner";

export function Root() {
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    if (!window.Android) {
      if (!location.pathname.startsWith("/console") && !location.pathname.startsWith("/node")) {
        navigate("/console", { replace: true });
      }
    } else {
      const hasSeenTutorial = localStorage.getItem("hasSeenTutorial");
      if (!hasSeenTutorial && location.pathname !== "/onboarding") {
        navigate("/onboarding", { replace: true });
      } else if (location.pathname === "/console") {
        navigate("/", { replace: true });
      }
    }
  }, [location, navigate]);

  return (
    <div className="w-full h-full bg-[#0B1220] font-sans selection:bg-[#2563EB]/30 overflow-hidden">
      <Toaster position="top-center" richColors theme="dark" />
      <Outlet />
    </div>
  );
}
