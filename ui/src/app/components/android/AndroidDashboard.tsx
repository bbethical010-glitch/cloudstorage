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
  const appState = useContext(AppStateContext);

  const isOnline = appState?.isNodeRunning ?? false;
  const folderName = appState?.folderName;
  const storageTotal = appState?.storageTotal;
  const storageUsed = appState?.storageUsed;
  
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

  const shareCode = appState?.shareCode || "----";

  return (
    <div className="min-h-screen bg-[#0B1220] text-[#E5E7EB] pb-24 font-sans selection:bg-[#2563EB]/30">
      {/* Header */}
      <div className="px-6 pt-10 pb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Access Node</h1>
          <p className="text-sm text-[#9CA3AF] mt-1 font-medium">Easy Storage Cloud</p>
        </div>
      </div>

      <div className="px-6 flex flex-col gap-6">
        
        {/* Block A: Node Status */}
        <Card className="bg-[#111827] border-[#374151] p-8 flex flex-col items-center justify-center text-center">
          <div 
            className={`w-28 h-28 rounded-full flex items-center justify-center cursor-pointer transition-colors ${
              isOnline ? "bg-[#2563EB]" : "bg-[#1F2937]"
            }`}
            onClick={handleToggleNode}
          >
            <Power className="w-12 h-12 text-white" />
          </div>
          <div className="mt-6 flex flex-col items-center gap-2">
            <h2 className="text-2xl font-bold">
              {isOnline ? "Node Active" : "Node Offline"}
            </h2>
            {isOnline && appState?.tunnelStatus && (
              <Badge variant="outline" className={`px-3 py-1 text-xs font-bold border rounded-full ${
                appState.tunnelStatus === 'Connected' ? 'border-[#22C55E] text-[#22C55E] bg-[#22C55E]/10' :
                appState.tunnelStatus === 'Connecting' ? 'border-yellow-500 text-yellow-500 bg-yellow-500/10' :
                'border-red-500 text-red-500 bg-red-500/10'
              }`}>
                {appState.tunnelStatus === 'Connected' ? 'TUNNEL CONNECTED' :
                 appState.tunnelStatus === 'Connecting' ? 'CONNECTING...' : 'ERROR'}
              </Badge>
            )}
          </div>
        </Card>

        {/* Block B: Storage Card */}
        <Card className="bg-[#111827] border-[#374151] p-6 lg:p-8">
          <div className="flex items-center justify-between mb-6">
            <h3 className="text-xs font-bold text-[#9CA3AF] uppercase tracking-widest">Storage Status</h3>
          </div>
          
          {storageTotal === undefined ? (
            <div className="flex items-center gap-6 animate-pulse">
              <div className="w-20 h-20 rounded-full bg-[#1F2937] shrink-0" />
              <div className="space-y-3 flex-1">
                <div className="h-6 w-32 bg-[#1F2937] rounded" />
                <div className="h-4 w-24 bg-[#1F2937] rounded" />
              </div>
            </div>
          ) : storageTotal === 0 ? (
            <div className="flex flex-col items-center justify-center py-6">
              <span className="text-sm font-bold text-[#9CA3AF] mb-4">Storage unavailable</span>
              <Button onClick={() => androidBridge.selectFolder()} variant="outline" className="border-[#374151] rounded-xl font-bold text-xs h-10 px-6 bg-[#1F2937]/50 hover:bg-[#1F2937]">
                 Select Directory
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-6">
               <div className="relative w-20 h-20 flex items-center justify-center shrink-0">
                 <svg className="w-full h-full -rotate-90">
                  <circle cx="40" cy="40" r="34" fill="transparent" stroke="#1F2937" strokeWidth="8" />
                  <motion.circle
                    cx="40" cy="40" r="34" fill="transparent" stroke="#2563EB" strokeWidth="8"
                    strokeDasharray={213.6}
                    initial={{ strokeDashoffset: 213.6 }}
                    animate={{ strokeDashoffset: 213.6 * (1 - displayUsagePercent / 100) }}
                    transition={{ duration: 1.5, ease: "easeOut" }}
                    strokeLinecap="round"
                  />
                </svg>
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className="text-sm font-black text-white">{Math.round(displayUsagePercent)}%</span>
                </div>
              </div>
              
              <div className="flex-1 min-w-0">
                <div className="flex items-end gap-2 shrink-0">
                  <span className="text-2xl sm:text-3xl font-black text-white tracking-tight">{displayStorageUsedGB} GB</span>
                  <span className="text-sm font-medium text-[#9CA3AF] mb-1">/ {displayStorageTotalGB} GB</span>
                </div>
                <div className="text-xs font-bold text-[#22C55E] uppercase tracking-wider mt-2">
                  {displayStorageFreeGB} GB Free
                </div>
              </div>
            </div>
          )}
        </Card>

        {/* Block C: Actions */}
        <div className="grid grid-cols-2 gap-4">
          <Button 
            onClick={() => androidBridge.shareInvite()} 
            className="flex-1 bg-[#111827] hover:bg-[#1F2937] border border-[#374151] rounded-2xl h-14 text-sm font-bold flex gap-3 text-white transition-colors"
          >
            <Share2 className="w-5 h-5 text-[#A855F7]" />
            Share Link
          </Button>
          <Button 
            onClick={() => androidBridge.copyToClipboard(shareCode, "ID Copied")} 
            className="flex-1 bg-[#111827] hover:bg-[#1F2937] border border-[#374151] rounded-2xl h-14 text-sm font-bold flex gap-3 text-white transition-colors"
          >
            <Link2 className="w-5 h-5 text-[#2563EB]" />
            Copy ID
          </Button>
          <Link to="/browser" className="flex-1">
            <Button className="w-full bg-[#111827] hover:bg-[#1F2937] border border-[#374151] rounded-2xl h-14 text-sm font-bold flex gap-3 text-white transition-colors">
              <FolderOpen className="w-5 h-5 text-[#EAB308]" />
              Explorer
            </Button>
          </Link>
          <Link to="/settings" className="flex-1">
            <Button className="w-full bg-[#111827] hover:bg-[#1F2937] border border-[#374151] rounded-2xl h-14 text-sm font-bold flex gap-3 text-white transition-colors">
              <Server className="w-5 h-5 text-[#10B981]" />
              Settings
            </Button>
          </Link>
        </div>
      </div>
    </div>
  );
}
