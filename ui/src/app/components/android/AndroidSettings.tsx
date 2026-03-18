import { useNavigate } from "react-router";
import { motion, AnimatePresence } from "motion/react";
import {
  ChevronLeft,
  Shield,
  Clock,
  HardDrive,
  Share2,
  HelpCircle,
  LogOut,
  ChevronRight,
  Monitor,
  Globe,
  Activity as ActivityIcon,
  FolderOpen,
  Wifi,
  Save,
  X,
  Bell
} from "lucide-react";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Link } from "react-router";
import { useContext, useState, useEffect } from "react";
import { AppStateContext } from "../../App";
import { androidBridge } from "../../bridge";
import { toast } from "sonner";

interface SettingsItem {
  icon: any;
  label: string;
  value?: string;
  path?: string;
  onClick?: () => void;
}

interface SettingsSection {
  title: string;
  items: SettingsItem[];
}

export function AndroidSettings() {
  const navigate = useNavigate();
  const appState = useContext(AppStateContext);
  
  const [editingRelay, setEditingRelay] = useState(false);
  const [relayInput, setRelayInput] = useState(appState?.relayBaseUrl || "");

  const [settings, setSettings] = useState(() => {
    const saved = localStorage.getItem('appSettings');
    return saved ? JSON.parse(saved) : {
      theme: 'Dark',
      language: 'English',
      bgMode: 'Optimized',
      notifications: 'All Alerts'
    };
  });

  useEffect(() => {
    localStorage.setItem('appSettings', JSON.stringify(settings));
    if (settings.theme === 'Light') {
      document.documentElement.classList.remove('dark');
    } else {
      document.documentElement.classList.add('dark');
    }
  }, [settings]);

  const toggleSetting = (key: keyof typeof settings, options: string[]) => {
    setSettings((prev: any) => {
      const idx = options.indexOf(prev[key]);
      const nextIdx = (idx + 1) % options.length;
      return { ...prev, [key]: options[nextIdx] };
    });
  };

  useEffect(() => {
    if (!editingRelay) {
      setRelayInput(appState?.relayBaseUrl || "");
    }
  }, [appState?.relayBaseUrl, editingRelay]);

  const handleUpdateRelay = () => {
    if (!relayInput.startsWith("http")) {
      toast.error("Invalid relay URL (must start with http/https)");
      return;
    }
    androidBridge.updateRelayBaseUrl(relayInput);
    setEditingRelay(false);
    toast.success("Relay endpoint updated");
  };

  const sections: SettingsSection[] = [
    {
      title: "General",
      items: [
        { icon: Monitor, label: "App Theme", value: settings.theme, onClick: () => toggleSetting('theme', ['Dark', 'Light']) },
        { icon: Globe, label: "Language", value: settings.language, onClick: () => toggleSetting('language', ['English', 'Spanish', 'French']) },
        { icon: ActivityIcon, label: "Background Service Mode", value: settings.bgMode, onClick: () => toggleSetting('bgMode', ['Optimized', 'Unrestricted']) },
        { icon: Bell, label: "Notification Preferences", value: settings.notifications, onClick: () => toggleSetting('notifications', ['All Alerts', 'None']) },
      ]
    },
    {
      title: "Storage & Network",
      items: [
        { 
          icon: HardDrive, 
          label: "Storage Location", 
          value: appState?.folderName || "Not selected",
          onClick: () => androidBridge.selectFolder()
        },
        { icon: Shield, label: "Storage Permissions", value: "Granted" },
        { icon: Clock, label: "Sync Logs", value: "2 days ago" },
      ]
    },
    {
      title: "Security",
      items: [
        { icon: Shield, label: "Security Settings", value: "Standard" },
        { icon: Shield, label: "Node Visibility", value: "Encrypted" },
        { icon: Share2, label: "Invite Friends", onClick: () => androidBridge.shareInvite() },
      ]
    }
  ];

  return (
    <div className="min-h-screen bg-[#0B1220] text-[#E5E7EB] pb-32">
      {/* Header */}
      <div className="px-6 pt-10 pb-4 flex items-center gap-4">
        <Button 
          variant="ghost" 
          size="icon" 
          onClick={() => navigate(-1)}
          className="rounded-full hover:bg-[#1F2937]"
        >
          <ChevronLeft className="w-6 h-6" />
        </Button>
        <h1 className="text-xl font-bold">Settings</h1>
      </div>

      <div className="px-6 py-4 space-y-8">
        {/* Relay Card (Custom) */}
        <section className="space-y-3">
          <h3 className="text-xs font-bold text-[#9CA3AF] uppercase tracking-widest px-1">Cloud Access</h3>
          <Card className="bg-[#111827] border-[#374151] p-4">
            <div className="flex items-center gap-4 mb-4">
              <div className="w-10 h-10 rounded-xl bg-[#0B1220] flex items-center justify-center text-[#A855F7]">
                <Wifi className="w-5 h-5" />
              </div>
              <div className="flex-1">
                <span className="text-sm font-medium">Connection Endpoint</span>
                <p className="text-[10px] text-[#9CA3AF] mt-0.5">Secure gateway for global file access</p>
              </div>
              <Button 
                variant="ghost" 
                size="sm" 
                onClick={() => setEditingRelay(!editingRelay)}
                className="text-[#2563EB] hover:text-[#2563EB] hover:bg-[#2563EB]/10"
              >
                {editingRelay ? "Cancel" : "Change"}
              </Button>
            </div>
            
            <AnimatePresence mode="wait">
              {editingRelay ? (
                <motion.div 
                  initial={{ opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: "auto" }}
                  exit={{ opacity: 0, height: 0 }}
                  className="space-y-3 overflow-hidden"
                >
                  <Input 
                    value={relayInput}
                    onChange={(e) => setRelayInput(e.target.value)}
                    placeholder="https://relay.example.com"
                    className="bg-[#0B1220] border-[#374151] h-12 rounded-xl text-xs"
                  />
                  <div className="flex gap-2">
                    <Button 
                      onClick={handleUpdateRelay}
                      className="flex-1 bg-[#2563EB] hover:bg-[#1D4ED8] rounded-xl h-10 text-xs font-bold"
                    >
                      <Save className="w-3.5 h-3.5 mr-2" />
                      Save Configuration
                    </Button>
                    <Button 
                      variant="ghost" 
                      onClick={() => setEditingRelay(false)}
                      className="px-3 border border-[#374151] rounded-xl"
                    >
                      <X className="w-4 h-4" />
                    </Button>
                  </div>
                </motion.div>
              ) : (
                <div className="bg-[#0B1220] border border-[#374151] rounded-xl p-3">
                  <span className="text-[11px] font-mono text-[#2563EB] break-all">
                    {appState?.relayBaseUrl || "No relay configured"}
                  </span>
                </div>
              )}
            </AnimatePresence>
          </Card>
        </section>

        {/* Node Health Indicator */}
        <section className="space-y-3">
          <h3 className="text-xs font-bold text-[#9CA3AF] uppercase tracking-widest px-1">Node Health</h3>
          <div className="grid grid-cols-2 gap-3">
            <Card className="bg-[#111827] border-[#374151] p-4 flex flex-col gap-2">
              <div className="flex justify-between items-center">
                <span className="text-xs text-[#9CA3AF] font-medium">CPU Usage</span>
                <ActivityIcon className="w-4 h-4 text-[#2563EB]" />
              </div>
              <span className="text-xl font-bold text-[#E5E7EB]">{appState?.health?.cpu || "0%"}</span>
            </Card>
            <Card className="bg-[#111827] border-[#374151] p-4 flex flex-col gap-2">
              <div className="flex justify-between items-center">
                <span className="text-xs text-[#9CA3AF] font-medium">Memory</span>
                <HardDrive className="w-4 h-4 text-[#10B981]" />
              </div>
              <span className="text-xl font-bold text-[#E5E7EB]">{appState?.health?.memory || "0 MB"}</span>
            </Card>
            <Card className="bg-[#111827] border-[#374151] p-4 flex flex-col gap-2">
              <div className="flex justify-between items-center">
                <span className="text-xs text-[#9CA3AF] font-medium">Network Ping</span>
                <Wifi className="w-4 h-4 text-[#A855F7]" />
              </div>
              <span className="text-xl font-bold text-[#E5E7EB]">{appState?.health?.ping || "0 ms"}</span>
            </Card>
            <Card className="bg-[#111827] border-[#374151] p-4 flex flex-col gap-2">
              <div className="flex justify-between items-center">
                <span className="text-xs text-[#9CA3AF] font-medium">Storage IO</span>
                <Save className="w-4 h-4 text-[#F59E0B]" />
              </div>
              <span className="text-xl font-bold text-[#E5E7EB]">{appState?.health?.io || "Idle"}</span>
            </Card>
          </div>
        </section>

        {sections.map((section: SettingsSection, idx) => (
          <section key={idx} className="space-y-3">
            <h3 className="text-xs font-bold text-[#9CA3AF] uppercase tracking-widest px-1">{section.title}</h3>
            <Card className="bg-[#111827] border-[#374151] divide-y divide-[#374151]/30 overflow-hidden">
              {section.items.map((item: SettingsItem, i) => (
                <button 
                  key={i} 
                  onClick={() => item.onClick ? item.onClick() : item.path ? navigate(item.path) : null}
                  className="w-full flex items-center justify-between p-4 hover:bg-[#1F2937] transition-colors text-left"
                >
                  <div className="flex items-center gap-4">
                    <div className="w-10 h-10 rounded-xl bg-[#0B1220] flex items-center justify-center text-[#2563EB]">
                      <item.icon className="w-5 h-5" />
                    </div>
                    <span className="text-sm font-medium">{item.label}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    {('value' in item) && <span className="text-xs text-[#9CA3AF] tracking-tight">{item.value as string}</span>}
                    <ChevronRight className="w-4 h-4 text-[#374151]" />
                  </div>
                </button>
              ))}
            </Card>
          </section>
        ))}

        <Button 
          variant="ghost" 
          className="w-full text-[#EF4444] hover:text-[#EF4444] hover:bg-[#EF4444]/5 h-14 rounded-2xl flex gap-3 font-bold"
        >
          <LogOut className="w-5 h-5" />
          Sign Out Node
        </Button>
      </div>

      {/* Bottom Nav */}
      <div className="fixed bottom-6 left-6 right-6 h-20 bg-[#111827]/80 backdrop-blur-xl border border-[#374151] rounded-[2.5rem] flex items-center justify-around shadow-2xl z-50">
        <Link to="/" className="p-4 text-[#9CA3AF] hover:text-[#E5E7EB] flex flex-col items-center gap-1">
          <ActivityIcon className="w-6 h-6" />
        </Link>
        <Link to="/browser" className="p-4 text-[#9CA3AF] hover:text-[#E5E7EB] flex flex-col items-center gap-1">
          <FolderOpen className="w-6 h-6" />
        </Link>
      </div>
    </div>
  );
}
