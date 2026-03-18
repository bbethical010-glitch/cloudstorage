import { Link } from "react-router";
import { motion } from "motion/react";
import {
  Server,
  Link2,
  FolderOpen,
  Share2,
  Power
} from "lucide-react";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { useContext } from "react";
import { toast } from "sonner";
import { AppStateContext } from "../../App";
import { androidBridge } from "../../bridge";

export function AndroidDashboard() {
  const ctx = useContext(AppStateContext);
  const appState = ctx?.state;

  const isOnline = appState?.node?.isRunning ?? false;
  const folderName = appState?.node?.folderName;
  const storageTotal = appState?.storage?.totalBytes;
  const storageUsed = appState?.storage?.usedBytes;
  
  const displayStorageTotalGB = storageTotal !== undefined ? (storageTotal / Math.pow(1024, 3)).toFixed(2) : "0.00";
  const displayStorageUsedGB = storageUsed !== undefined ? (storageUsed / Math.pow(1024, 3)).toFixed(2) : "0.00";
  const displayStorageFreeGB = storageTotal !== undefined && storageUsed !== undefined ? Math.max(0, (storageTotal - storageUsed) / Math.pow(1024, 3)).toFixed(2) : "0.00";
  const displayUsagePercent = storageTotal && storageTotal > 0 && storageUsed ? (storageUsed / storageTotal) * 100 : 0;

  const handleToggleNode = () => {
    if (!folderName || folderName === "No storage selected") {
      toast.error("Please select a storage folder first");
      androidBridge.selectFolder();
      return;
    }
    androidBridge.toggleNode();
    toast.info(isOnline ? "Shutting down engine..." : "Launching engine...");
  };

  const shareCode = appState?.node?.shareCode || "----";

  return (
    <div className="min-h-screen bg-[#0B1220] text-[#E5E7EB] pb-24 font-sans selection:bg-[#2563EB]/30">
      {/* Top Card: Node Controller */}
      <div className="px-6 pt-10 pb-6">
        <Card className="bg-[#111827] border-[#1F2937] p-8 flex flex-col items-center text-center">
          <div 
            onClick={handleToggleNode}
            className={`w-28 h-28 rounded-full flex items-center justify-center cursor-pointer transition-colors shadow-2xl ${
              isOnline ? "bg-[#2563EB] shadow-[#2563EB]/20" : "bg-[#1F2937] shadow-black/50"
            }`}
          >
            <Power className="w-12 h-12 text-white" />
          </div>
          <div className="mt-8 flex flex-col items-center gap-2 w-full">
            <span className="text-[10px] font-bold text-[#9CA3AF] uppercase tracking-[0.2em]">
              {isOnline ? "STOP NODE" : "START NODE"}
            </span>
            <h2 className="text-2xl font-bold text-white tracking-tight">
              {folderName || "No Storage"} • {displayStorageTotalGB} GB
            </h2>
            <span className="text-[10px] font-bold text-[#9CA3AF] uppercase tracking-[0.2em]">
              Local Only Mode
            </span>
          </div>

          <div className="mt-8 grid grid-cols-2 gap-4 w-full">
            <Button 
              onClick={() => androidBridge.shareInvite()} 
              variant="outline"
              className="w-full bg-[#1F2937]/50 hover:bg-[#1F2937] border-transparent rounded-2xl h-12 text-[13px] font-medium flex gap-2 text-[#9CA3AF] justify-start px-4 transition-colors truncate"
            >
              <Share2 className="w-4 h-4 text-[#A855F7] shrink-0" />
              <span className="truncate">{appState?.node?.tunnelStatus === 'Connected' ? 'Share Tunnel' : 'Waiting for tunnel...'}</span>
            </Button>
            <Button 
              onClick={() => androidBridge.copyToClipboard(shareCode, "ID Copied")} 
              variant="outline"
              className="w-full bg-[#1F2937]/50 hover:bg-[#1F2937] border-transparent rounded-2xl h-12 text-[13px] font-medium flex justify-center gap-2 text-white transition-colors"
            >
              <Link2 className="w-4 h-4 text-[#2563EB]" />
              Copy ID
            </Button>
          </div>
        </Card>
      </div>

      <div className="px-6 flex flex-col gap-6">
        {/* Second Card: Connected Storage */}
        <Card className="bg-[#111827] border-[#1F2937] p-6 lg:p-8">
          <div className="flex items-center justify-between mb-8">
            <h3 className="text-[11px] font-bold text-[#9CA3AF] uppercase tracking-wider">Connected Storage</h3>
            <Badge variant="outline" className="px-2 py-0.5 text-[9px] font-bold border-[#059669] text-[#10B981] bg-[#059669]/10 rounded-full tracking-wider">
              READY
            </Badge>
          </div>
          
          <div className="flex items-center gap-6">
             <div className="relative w-20 h-20 flex items-center justify-center shrink-0">
               <svg className="w-full h-full -rotate-90">
                <circle cx="40" cy="40" r="34" fill="transparent" stroke="#1F2937" strokeWidth="6" />
                <motion.circle
                  cx="40" cy="40" r="34" fill="transparent" stroke="#2563EB" strokeWidth="6"
                  strokeDasharray={213.6}
                  initial={{ strokeDashoffset: 213.6 }}
                  animate={{ strokeDashoffset: 213.6 * (1 - displayUsagePercent / 100) }}
                  transition={{ duration: 1.5, ease: "easeOut" }}
                  strokeLinecap="round"
                />
              </svg>
              <div className="absolute inset-0 flex items-center justify-center">
                <span className="text-xs font-bold text-white">{Math.round(displayUsagePercent)}%</span>
              </div>
            </div>
            
            <div className="flex-1 min-w-0 flex flex-col items-start">
              <div className="flex items-baseline gap-2 shrink-0">
                <span className="text-2xl font-bold text-white tracking-tight">{displayStorageUsedGB} GB</span>
                <span className="text-xs font-medium text-[#9CA3AF]">/ {displayStorageTotalGB} GB</span>
              </div>
              <div className="text-[11px] font-bold text-[#10B981] mt-1 mb-3">
                {displayStorageFreeGB} GB FREE
              </div>
              <Button onClick={() => androidBridge.selectFolder()} variant="outline" className="w-full bg-[#0B1220]/50 hover:bg-[#0B1220] border-[#1F2937] rounded-full h-9 text-xs font-medium flex justify-between px-4 transition-colors">
                <div className="flex items-center gap-2 truncate">
                  <FolderOpen className="w-4 h-4 text-[#2563EB]" />
                  <span className="truncate text-white">{folderName || "Select Folder"}</span>
                </div>
                <span className="text-[#6B7280] ml-2">&gt;</span>
              </Button>
            </div>
          </div>
        </Card>

        {/* Third Row: Modules */}
        <div className="grid grid-cols-2 gap-4">
          <Link to="/browser" className="flex-1">
            <Card className="bg-[#111827] hover:bg-[#1F2937] border-[#1F2937] p-6 flex flex-col items-center justify-center gap-3 transition-colors h-full rounded-3xl">
              <div className="w-10 h-10 rounded-xl bg-[#2563EB]/10 flex items-center justify-center">
                <FolderOpen className="w-5 h-5 text-[#3B82F6]" />
              </div>
              <span className="text-xs font-bold text-white">Explorer</span>
            </Card>
          </Link>
          <Link to="/settings" className="flex-1">
            <Card className="bg-[#111827] hover:bg-[#1F2937] border-[#1F2937] p-6 flex flex-col items-center justify-center gap-3 transition-colors h-full rounded-3xl">
              <div className="w-10 h-10 rounded-xl bg-[#A855F7]/10 flex items-center justify-center">
                <Server className="w-5 h-5 text-[#A855F7]" />
              </div>
              <span className="text-xs font-bold text-white">Settings</span>
            </Card>
          </Link>
        </div>

        {/* Fourth Section: System Info */}
        <div className="mt-2">
          <h3 className="text-[11px] font-bold text-[#9CA3AF] uppercase tracking-widest mb-4">System Information</h3>
          <Card className="bg-[#111827] border-[#1F2937] p-5 flex items-center gap-4 rounded-3xl">
            <div className={`w-12 h-12 rounded-2xl flex items-center justify-center shrink-0 ${isOnline ? 'bg-[#059669]/10' : 'bg-red-500/10'}`}>
              <svg className={`w-6 h-6 ${isOnline ? 'text-[#10B981]' : 'text-red-500'}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
              </svg>
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex justify-between items-center mb-0.5">
                <span className="text-[15px] font-bold text-white">{isOnline ? "Node Active" : "Node Offline"}</span>
                <span className="text-[9px] font-bold text-[#6B7280] uppercase tracking-wider">Just Now</span>
              </div>
              <span className="text-xs text-[#9CA3AF] truncate block">
                {isOnline ? "Server is actively routing." : "Server is currently stopped."}
              </span>
            </div>
          </Card>
        </div>
      </div>

      {/* Bottom Navigation */}
      <div className="fixed bottom-6 left-6 right-6">
        <Card className="bg-[#111827]/90 backdrop-blur-md border-[#1F2937] rounded-full p-2 flex justify-around items-center">
          <Link to="/dashboard" className="flex-1 flex justify-center py-2">
            <svg className="w-6 h-6 text-[#2563EB]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
            </svg>
          </Link>
          <Link to="/browser" className="flex-1 flex justify-center py-2 text-[#6B7280] hover:text-[#9CA3AF] transition-colors">
            <FolderOpen className="w-6 h-6" />
          </Link>
        </Card>
      </div>
    </div>
  );
}
