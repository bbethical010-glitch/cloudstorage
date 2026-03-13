import { Outlet, Link, useLocation, useNavigate } from "react-router";
import { Smartphone, Monitor } from "lucide-react";
import { Button } from "./ui/button";
import { useEffect } from "react";
import { Toaster } from "sonner";

export function Root() {
  const location = useLocation();
  const navigate = useNavigate();
  const isWebConsole = location.pathname === "/console";

  useEffect(() => {
    const hasSeenTutorial = localStorage.getItem("hasSeenTutorial");
    if (!hasSeenTutorial && location.pathname !== "/onboarding") {
      navigate("/onboarding");
    }
  }, [location, navigate]);

  return (
    <div className="min-h-screen bg-[#0B1220] font-sans selection:bg-[#2563EB]/30">
      <Toaster position="top-center" richColors theme="dark" />
      
      {/* View Switcher (Subtle) */}
      <div className="fixed top-4 right-4 z-[60] flex gap-2 bg-[#111827]/40 backdrop-blur-md border border-[#374151] rounded-xl p-1 shadow-2xl opacity-40 hover:opacity-100 transition-opacity">
        <Link to="/">
          <Button
            variant={!isWebConsole ? "secondary" : "ghost"}
            size="sm"
            className="h-8 gap-2 rounded-lg text-[10px] font-bold tracking-widest uppercase"
          >
            <Smartphone className="w-3.5 h-3.5" />
            MOBILE
          </Button>
        </Link>
        <Link to="/console">
          <Button
            variant={isWebConsole ? "secondary" : "ghost"}
            size="sm"
            className="h-8 gap-2 rounded-lg text-[10px] font-bold tracking-widest uppercase"
          >
            <Monitor className="w-3.5 h-3.5" />
            WEB
          </Button>
        </Link>
      </div>

      <Outlet />
    </div>
  );
}
