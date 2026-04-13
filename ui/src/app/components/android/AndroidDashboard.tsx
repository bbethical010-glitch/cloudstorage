import { Link } from "react-router";
import { motion, AnimatePresence } from "motion/react";
import {
  Server,
  Link2,
  FolderOpen,
  Power,
  QrCode
} from "lucide-react";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Switch } from "../ui/switch";
import { useContext, useState } from "react";
import { toast } from "sonner";
import { AppStateContext } from "../../App";
import { androidBridge } from "../../bridge";
import { ShareQRDialog } from "./ShareQRDialog";
import "../../../styles/node-switch.css";

export function AndroidDashboard() {
  const ctx = useContext(AppStateContext);
  const appState = ctx?.state;
  const [isRemoteAccessEnabled, setIsRemoteAccessEnabled] = useState(false);
  const [showShareQR, setShowShareQR] = useState(false);

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
    
    const appSet = JSON.parse(localStorage.getItem('appSettings') || '{}');
    if (appSet.notifications !== 'None') {
       androidBridge.showNotification("Node Engine", isOnline ? "Node Stopped natively" : "Node Started independently");
    }
  };

  const shareCode = appState?.node?.shareCode || "----";

  return (
    <div className="min-h-screen bg-[#0B1220] text-[#E5E7EB] pb-24 font-sans selection:bg-[#2563EB]/30">
      <div className="max-w-3xl mx-auto w-full">
        {/* Top Card: Node Controller */}
        <div className="px-6 pt-10 pb-6">
          <Card className="bg-[#111827] border-[#1F2937] p-8 flex flex-col items-center text-center">
            
            <div className="node-switch-container mb-8">
              <input 
                type="checkbox" 
                id="checkbox"
                checked={isOnline}
                onChange={handleToggleNode}
              />
              <label htmlFor="checkbox" className="switch">
                {isOnline ? (
                  <Server className="w-5 h-5 text-white" />
                ) : (
                  <Power className="w-5 h-5 text-white" />
                )}
                <span>{isOnline ? "Node Running" : "Launch Engine"}</span>
              </label>
            </div>

            <div className="flex flex-col items-center gap-2 w-full max-w-md mx-auto">
              <span className="text-[10px] font-bold text-[#9CA3AF] uppercase tracking-[0.2em]">
                {isOnline ? "STATUS: ACTIVE" : "STATUS: OFFLINE"}
              </span>
              <h2 className="text-2xl font-bold text-white tracking-tight">
                {folderName || "No Storage"} • {displayStorageTotalGB} GB
              </h2>
              <span className="text-[10px] font-bold text-[#9CA3AF] uppercase tracking-[0.2em]">
                {isOnline ? "Engine Active" : "Ready to Launch"}
              </span>
            </div>

            <div className="mt-8 grid grid-cols-2 gap-4 w-full max-w-md mx-auto">
              <Button 
                onClick={() => {
                  if (!isOnline) {
                    toast.error("Launch the engine first.");
                    return;
                  }
                  setShowShareQR(true);
                }} 
                variant="outline"
                className={`w-full border-transparent rounded-2xl h-12 text-[13px] font-medium flex gap-2 justify-start px-4 transition-colors truncate ${isOnline ? 'bg-[#1F2937]/80 hover:bg-[#1F2937] text-white' : 'bg-[#111827] opacity-60 text-[#6B7280]'}`}
              >
                <QrCode className={`w-4 h-4 shrink-0 ${isOnline ? 'text-[#A855F7]' : 'text-[#4B5563]'}`} />
                <span className="truncate">Share & QR</span>
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

        <div className="px-6 flex flex-col gap-6 max-w-2xl mx-auto">
          {/* Remote Access Card */}
          <Card className="bg-[#111827] border-[#1F2937] p-6 lg:p-8">
            <div className="flex items-start justify-between">
              <div>
                <h3 className="text-[13px] font-bold text-white tracking-wider flex items-center gap-2">
                  <Link2 className="w-4 h-4 text-[#A855F7]" />
                  REMOTE TUNNEL
                </h3>
                <p className="text-[11px] text-[#9CA3AF] mt-1">Enable secure access from anywhere.</p>
              </div>
              <Switch 
                checked={isRemoteAccessEnabled} 
                onCheckedChange={setIsRemoteAccessEnabled}
                disabled={appState?.node?.tunnelStatus !== 'Connected'}
              />
            </div>
            <AnimatePresence>
              {isRemoteAccessEnabled && appState?.node?.tunnelStatus === 'Connected' && (
                <motion.div 
                  initial={{ opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: 'auto' }}
                  exit={{ opacity: 0, height: 0 }}
                  className="overflow-hidden mt-4 border-t border-[#1F2937] pt-4"
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <div className="w-2 h-2 rounded-full bg-[#10B981] animate-pulse" />
                      <span className="text-xs text-[#10B981] font-bold">Tunnel Active</span>
                    </div>
                    <Button 
                      onClick={() => setShowShareQR(true)}
                      variant="ghost"
                      className="h-8 px-3 rounded-lg text-[11px] font-bold text-[#A855F7] hover:bg-[#A855F7]/10"
                    >
                      <QrCode className="w-3.5 h-3.5 mr-1.5" /> View QR & Links
                    </Button>
                  </div>
                  <p className="text-[10px] font-mono text-[#6B7280] mt-2 break-all bg-[#0B1220] py-2 px-3 rounded-lg border border-[#1F2937]">
                    https://{appState.node.relayBaseUrl}/node/{shareCode}
                  </p>
                </motion.div>
              )}
            </AnimatePresence>
          </Card>

          {/* Second Card: Connected Storage */}
          <Card className="bg-[#111827] border-[#1F2937] p-6 lg:p-8">
            <div className="flex items-center justify-between mb-8">
              <h3 className="text-[11px] font-bold text-[#9CA3AF] uppercase tracking-wider">Connected Storage</h3>
              <Badge variant="outline" className="px-2 py-0.5 text-[9px] font-bold border-[#059669] text-[#10B981] bg-[#059669]/10 rounded-full tracking-wider">
                READY
              </Badge>
            </div>
            
            <div className="flex items-center gap-6">
               <div className="relative w-20 h-20 md:w-32 md:h-32 max-w-72 max-h-72 flex items-center justify-center shrink-0 mx-auto">
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
      </div>

      {/* Share QR Dialog */}
      <ShareQRDialog
        open={showShareQR}
        onClose={() => setShowShareQR(false)}
        relayBaseUrl={appState?.node?.relayBaseUrl || ""}
        shareCode={shareCode}
        lanUrl={appState?.node?.lanUrl}
        tunnelConnected={appState?.node?.tunnelStatus === 'Connected'}
      />

    </div>
  );
}
