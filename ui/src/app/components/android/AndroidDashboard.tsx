import { Link } from "react-router";
import { motion } from "motion/react";
import {
  Server,
  HardDrive,
  Link2,
  ExternalLink,
  FolderOpen,
  Activity,
  ChevronRight,
  Clock,
  LayoutGrid,
  Share2,
  Power,
  Search,
  Usb,
  ShieldCheck,
  Wifi,
  Globe
} from "lucide-react";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { useContext } from "react";
import { toast } from "sonner";
import { AppStateContext } from "../../App";
import { androidBridge } from "../../bridge";

export function AndroidDashboard() {
  const appState = useContext(AppStateContext);
  
  const isOnline = appState?.isNodeRunning ?? false;
  const folderName = appState?.folderName || "No storage selected";
  const shareCode = appState?.shareCode || "----";
  
  const usagePercent = appState?.usagePercent ?? 0;
  const storageUsed = appState?.storageUsed ?? 0;
  const storageTotal = appState?.storageTotal ?? 0;

  const activities = [
  { 
    id: "1", 
    type: "status", 
    title: "Cloud Connection Active", 
    description: "Your storage is now accessible through the secure web link.", 
    time: "Just now", 
    icon: Globe,
    color: "text-[#2563EB]"
  },
  { 
    id: "2", 
    type: "storage", 
    title: "Storage Ready", 
    description: "External drive is connected and ready for file access.", 
    time: "2 mins ago", 
    icon: ShieldCheck,
    color: "text-[#22C55E]"
  },
  { 
    id: "3", 
    type: "security", 
    title: "Secure Node Initialized", 
    description: "Unique device ID generated for private storage access.", 
    time: "5 mins ago", 
    icon: Wifi,
    color: "text-[#A855F7]"
  }
];

  const handleToggleNode = () => {
    if (!appState?.folderName) {
      toast.error("Please select a storage folder first");
      androidBridge.selectFolder();
      return;
    }
    androidBridge.toggleNode();
    toast.info(isOnline ? "Shutting down engine..." : "Launching engine...");
  };

  const handleSelectFolder = () => {
    androidBridge.selectFolder();
  };

  const handleShare = () => {
    androidBridge.shareInvite();
  };

  const copyShareCode = () => {
    androidBridge.copyToClipboard(shareCode, "Node access code copied!");
  };

  return (
    <div className="min-h-screen bg-[#0B1220] text-[#E5E7EB] pb-24">
      {/* Header */}
      <div className="px-6 pt-10 pb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">My Storage</h1>
          <p className="text-sm text-[#9CA3AF] mt-1">Personal Cloud Node</p>
        </div>
        <motion.div 
          onClick={handleToggleNode}
          className={`p-3 rounded-2xl shadow-lg cursor-pointer transition-all ${
            isOnline 
              ? "bg-gradient-to-br from-[#22C55E] to-[#10B981] shadow-green-500/20" 
              : "bg-gradient-to-br from-[#EF4444] to-[#B91C1C] shadow-red-500/20"
          }`}
          whileTap={{ scale: 0.9 }}
        >
          <Power className="w-6 h-6 text-white" />
        </motion.div>
      </div>

      <div className="px-6 space-y-6">
        {/* Node Control Card */}
        <Card className="bg-[#111827] border-[#374151] p-6 relative overflow-hidden">
          {!appState?.folderName ? (
            <div className="flex flex-col items-center text-center space-y-4 py-4">
              <div className="w-20 h-20 bg-[#1F2937] rounded-3xl flex items-center justify-center border border-[#374151] shadow-inner mb-2">
                <Usb className="w-10 h-10 text-[#374151]" />
              </div>
              <h2 className="text-xl font-bold">No Storage Connected</h2>
              <p className="text-xs text-[#9CA3AF] max-w-[200px] leading-relaxed">
                Connect a USB drive or memory card to start using Easy Storage Cloud.
              </p>
              <Button 
                onClick={handleSelectFolder}
                className="bg-[#2563EB] hover:bg-[#1d4ed8] text-white rounded-xl h-12 flex gap-2 px-6"
              >
                Connect Now
              </Button>
            </div>
          ) : (
            <div className="flex flex-col items-center text-center space-y-4">
              <div className="relative">
                <motion.div 
                  animate={isOnline ? { scale: [1, 1.1, 1], opacity: [0.5, 1, 0.5] } : {}}
                  transition={{ duration: 2, repeat: Infinity }}
                  className={`w-32 h-32 rounded-full flex items-center justify-center ${isOnline ? 'bg-blue-500/10' : 'bg-red-500/10'}`}
                >
                  <div 
                    className={`w-24 h-24 rounded-full flex items-center justify-center shadow-2xl cursor-pointer ${
                      isOnline 
                        ? "bg-gradient-to-br from-[#2563EB] to-[#1D4ED8] shadow-blue-500/30" 
                        : "bg-[#1F2937] border border-[#374151]"
                    }`}
                    onClick={handleToggleNode}
                  >
                    <Power className={`w-10 h-10 ${isOnline ? "text-white" : "text-[#9CA3AF]"}`} />
                  </div>
                </motion.div>
              </div>
              
              <div>
                <h2 className="text-xl font-bold">{isOnline ? "Node is Active" : "Node Stopped"}</h2>
                <p className="text-xs text-[#9CA3AF] mt-1 font-mono uppercase tracking-[0.2em]">
                  {isOnline ? "Broadcasting Storage" : "Local Only Mode"}
                </p>
              </div>

              <div className="w-full flex gap-3">
                <Button 
                  onClick={handleShare}
                  className="flex-1 bg-white/5 hover:bg-white/10 border-[#374151] rounded-2xl h-12 text-sm font-bold flex gap-2"
                >
                  <Share2 className="w-4 h-4 text-[#A855F7]" />
                  Share Link
                </Button>
                <Button 
                  onClick={copyShareCode}
                  className="flex-1 bg-white/5 hover:bg-white/10 border-[#374151] rounded-2xl h-12 text-sm font-bold flex gap-2"
                >
                  <Link2 className="w-4 h-4 text-[#2563EB]" />
                  Copy ID
                </Button>
              </div>
            </div>
          )}
        </Card>

        {/* Storage Details Card */}
        {appState?.folderName && (
          <Card className="bg-[#111827] border-[#374151] p-6">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-xs font-bold text-[#9CA3AF] uppercase tracking-widest">Connected Storage</h3>
              <Badge variant="outline" className="text-[10px] border-[#22C55E]/30 text-[#22C55E] bg-[#22C55E]/5 px-2 py-0.5 rounded-full font-bold">READY</Badge>
            </div>
            
            <div className="flex items-center gap-4">
               <div className="relative w-20 h-20 flex items-center justify-center shrink-0">
                 <svg className="w-full h-full -rotate-90">
                  <circle cx="40" cy="40" r="34" fill="transparent" stroke="#1F2937" strokeWidth="6" />
                  <motion.circle
                    cx="40" cy="40" r="34" fill="transparent" stroke="#2563EB" strokeWidth="6"
                    strokeDasharray={213.6}
                    initial={{ strokeDashoffset: 213.6 }}
                    animate={{ strokeDashoffset: 213.6 * (1 - usagePercent / 100) }}
                    transition={{ duration: 1.5, ease: "easeOut" }}
                    strokeLinecap="round"
                  />
                </svg>
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className="text-sm font-black text-white">{Math.round(usagePercent)}%</span>
                </div>
              </div>
              
              <div className="flex-1 min-w-0">
                <div className="flex items-end gap-1">
                  <span className="text-lg font-black text-white">{storageUsed} GB</span>
                  <span className="text-xs text-[#9CA3AF] mb-1">/ {storageTotal} GB</span>
                </div>
                <div 
                  className="mt-2 flex items-center gap-2 cursor-pointer bg-[#0B1220] px-3 py-2 rounded-xl border border-[#374151]/50"
                  onClick={handleSelectFolder}
                >
                  <HardDrive className="w-3.5 h-3.5 text-[#2563EB]" />
                  <span className="text-[10px] font-medium text-[#E5E7EB] truncate flex-1">
                    {folderName}
                  </span>
                  <ChevronRight className="w-3 h-3 text-[#374151]" />
                </div>
              </div>
            </div>
          </Card>
        )}

        {/* Navigation Grid */}
        <div className="grid grid-cols-2 gap-4">
          <Link to="/browser" className="flex-1">
            <Card className="bg-[#111827] border-[#374151] p-4 flex flex-col items-center gap-2 hover:border-[#2563EB]/50 transition-colors">
              <div className="w-10 h-10 rounded-xl bg-[#2563EB]/10 flex items-center justify-center text-[#2563EB]">
                <FolderOpen className="w-5 h-5" />
              </div>
              <span className="text-xs font-bold">Explorer</span>
            </Card>
          </Link>
          <Link to="/settings" className="flex-1">
            <Card className="bg-[#111827] border-[#374151] p-4 flex flex-col items-center gap-2 hover:border-[#2563EB]/50 transition-colors">
              <div className="w-10 h-10 rounded-xl bg-[#A855F7]/10 flex items-center justify-center text-[#A855F7]">
                <Server className="w-5 h-5" />
              </div>
              <span className="text-xs font-bold">Settings</span>
            </Card>
          </Link>
        </div>

        {/* Status Activity */}
        <div className="space-y-3">
          <h3 className="text-xs font-bold text-[#9CA3AF] uppercase tracking-widest px-1">System Information</h3>
          <Card className="bg-[#111827] border-[#374151] overflow-hidden">
            {activities.map((activity) => (
              <div key={activity.id} className="flex gap-4 p-4 bg-[#0B1220] rounded-2xl border border-[#374151]/30">
                <div className={`w-10 h-10 rounded-xl bg-[#111827] flex items-center justify-center ${activity.color}`}>
                  <activity.icon className="w-5 h-5" />
                </div>
                <div className="flex-1">
                  <div className="flex justify-between items-start">
                    <span className="text-sm font-bold text-[#E5E7EB]">{activity.title}</span>
                    <span className="text-[10px] font-mono text-[#4B5563] uppercase">{activity.time}</span>
                  </div>
                  <p className="text-xs text-[#9CA3AF] mt-1 leading-relaxed">{activity.description}</p>
                </div>
              </div>
            ))}
          </Card>
        </div>
      </div>

      {/* Bottom Nav */}
      <div className="fixed bottom-6 left-6 right-6 h-20 bg-[#111827]/80 backdrop-blur-xl border border-[#374151] rounded-[2.5rem] flex items-center justify-around shadow-2xl z-50">
        <Link to="/" className="p-4 text-[#2563EB] flex flex-col items-center gap-1">
          <Activity className="w-6 h-6" />
        </Link>
        <Link to="/browser" className="p-4 text-[#9CA3AF] hover:text-[#E5E7EB] flex flex-col items-center gap-1">
          <FolderOpen className="w-6 h-6" />
        </Link>
        <Link to="/activity" className="p-4 text-[#9CA3AF] hover:text-[#E5E7EB] flex flex-col items-center gap-1">
          <Clock className="w-6 h-6" />
        </Link>
      </div>
    </div>
  );
}
