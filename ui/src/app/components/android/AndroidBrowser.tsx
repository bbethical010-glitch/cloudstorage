import { useNavigate } from "react-router";
import { motion, AnimatePresence } from "motion/react";
import React, { useState, useEffect, useContext } from "react";
import {
  ChevronLeft,
  HardDrive,
  Upload,
  FolderPlus,
  Search,
  Folder,
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  MoreVertical,
  ArrowUpAz,
  Activity,
  FolderOpen,
  Clock,
  RefreshCw,
  AlertCircle,
  Plus
} from "lucide-react";
import { Link } from "react-router";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { AppStateContext } from "../../App";
import { toast } from "sonner";
import { FileDetails } from "./FileDetails";
import { androidBridge } from "../../bridge";

export function AndroidBrowser() {
  const navigate = useNavigate();
  const appState = useContext(AppStateContext);
  const [searchQuery, setSearchQuery] = useState("");
  const [showSearch, setShowSearch] = useState(false);
  const [files, setFiles] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedFile, setSelectedFile] = useState<any | null>(null);

  const isOnline = appState?.isNodeRunning ?? false;

  useEffect(() => {
    if (isOnline) {
      fetchFiles();
    } else {
      setFiles([]);
    }
  }, [isOnline]);

  const fetchFiles = async () => {
    setLoading(true);
    try {
      const response = await fetch("http://localhost:8080/api/files");
      if (!response.ok) throw new Error("Failed to fetch files");
      const data = await response.json();
      setFiles(data.map((f: any) => ({
        ...f,
        type: f.isDirectory ? "Folder" : "File",
        size: "Managed", // In a real app, this would be the actual size
        modified: "Synced Just Now"
      })));
    } catch (e) {
      console.error("Fetch failed", e);
      // Fallback for demo if needed, but we keep it empty for "true image"
    } finally {
      setLoading(false);
    }
  };

  const getFileIcon = (file: any) => {
    if (file.isDirectory) return <Folder className="w-6 h-6 text-[#2563EB]" />;
    const ext = file.name.split('.').pop()?.toLowerCase();
    
    if (['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(ext)) 
      return <ImageIcon className="w-6 h-6 text-[#A855F7]" />;
    if (['mp4', 'mov', 'avi', 'mkv'].includes(ext)) 
      return <Film className="w-6 h-6 text-orange-400" />;
    if (['mp3', 'wav'].includes(ext))
      return <Music className="w-6 h-6 text-pink-400" />;
    
    return <FileText className="w-6 h-6 text-[#9CA3AF]" />;
  };

  const filteredFiles = files.filter(f => 
    f.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="min-h-screen bg-[#0B1220] text-[#E5E7EB] pb-32">
      {/* Header */}
      <div className="px-6 pt-10 pb-4">
        <div className="flex items-center justify-between mb-6">
          <Button 
            variant="ghost" 
            size="icon" 
            onClick={() => navigate(-1)}
            className="rounded-full hover:bg-[#1F2937]"
          >
            <ChevronLeft className="w-6 h-6" />
          </Button>
          <div className="text-center">
            <h1 className="text-lg font-bold tracking-tight">Cloud Drive</h1>
            {!isOnline && (
              <div className="flex items-center gap-1 justify-center mt-0.5">
                <div className="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse" />
                <span className="text-[10px] text-red-500 font-bold uppercase tracking-widest">Offline</span>
              </div>
            )}
            {isOnline && (
               <div className="flex items-center gap-1 justify-center mt-0.5">
                <div className="w-1.5 h-1.5 rounded-full bg-[#22C55E]" />
                <span className="text-[10px] text-[#22C55E] font-bold uppercase tracking-widest">Connected</span>
              </div>
            )}
          </div>
          <div className="flex gap-1">
            <Button 
              variant="ghost" 
              size="icon" 
              onClick={fetchFiles}
              disabled={!isOnline || loading}
              className="rounded-full hover:bg-[#1F2937]"
            >
              <RefreshCw className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} />
            </Button>
            <Button 
              variant="ghost" 
              size="icon" 
              onClick={() => setShowSearch(!showSearch)}
              className={`rounded-full hover:bg-[#1F2937] ${showSearch ? 'text-[#2563EB]' : ''}`}
            >
              <Search className="w-5 h-5" />
            </Button>
          </div>
        </div>

        <AnimatePresence>
          {showSearch && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: "auto", opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              className="mb-6 overflow-hidden"
            >
              <Input
                placeholder="Search files..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="h-12 bg-[#111827] border-[#374151] rounded-2xl px-5 focus:ring-[#2563EB]"
              />
            </motion.div>
          )}
        </AnimatePresence>

        {/* Path Bar */}
        <div className="flex items-center gap-2 px-4 py-3 bg-[#111827] border border-[#374151] rounded-2xl mb-4">
          <HardDrive className="w-4 h-4 text-[#2563EB]" />
          <div className="flex items-center gap-1 text-[10px] font-mono text-[#9CA3AF] overflow-hidden">
            <span className="truncate">{appState?.folderName || "ROOT"}</span>
          </div>
        </div>
      </div>

      {/* File List */}
      <div className="px-6 space-y-4">
        {!isOnline && (
          <div className="py-20 flex flex-col items-center justify-center text-center px-10">
            <div className="w-20 h-20 bg-[#111827] border border-[#374151] rounded-full flex items-center justify-center mb-6 shadow-inner">
               <AlertCircle className="w-10 h-10 text-[#374151]" />
            </div>
            <h3 className="text-xl font-bold">Node Disconnected</h3>
            <p className="text-xs text-[#9CA3AF] mt-3 leading-relaxed">
              The storage node is currently offline. <br /> Start it from the dashboard to access files.
            </p>
            <Link to="/">
              <Button className="mt-8 rounded-xl h-12 bg-[#1F2937] hover:bg-[#374151] px-8">Return to Dashboard</Button>
            </Link>
          </div>
        )}

        {isOnline && filteredFiles.length === 0 && !loading && (
          <div className="py-20 flex flex-col items-center justify-center text-center">
            <div className="w-24 h-24 bg-[#111827] rounded-[2rem] flex items-center justify-center border border-[#374151] mb-6">
              <FolderOpen className="w-10 h-10 text-[#374151]" />
            </div>
            <h3 className="text-lg font-bold">This folder is empty</h3>
            <p className="text-sm text-[#9CA3AF] mt-2">Upload or create a folder to start.</p>
          </div>
        )}

        {isOnline && filteredFiles.map((file, i) => (
          <motion.div
            key={i}
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.03 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => setSelectedFile(file)}
          >
            <div className="bg-[#111827] border border-[#374151] p-4 flex items-center justify-between rounded-2xl active:bg-[#1F2937] transition-colors shadow-sm">
              <div className="flex items-center gap-4">
                <div className="w-14 h-14 rounded-2xl bg-[#0B1220] flex items-center justify-center border border-[#374151]/50 shadow-inner">
                  {getFileIcon(file)}
                </div>
                <div className="min-w-0">
                  <h3 className="text-sm font-bold truncate max-w-[180px] text-[#E5E7EB]">{file.name}</h3>
                  <div className="flex items-center gap-2 mt-1 px-1 py-0.5 bg-[#0B1220] w-fit rounded-md">
                    <span className="text-[9px] text-[#9CA3AF] font-bold uppercase tracking-widest">{file.size}</span>
                    <span className="w-1 h-1 rounded-full bg-[#374151]" />
                    <span className="text-[9px] text-[#9CA3AF]">{file.modified}</span>
                  </div>
                </div>
              </div>
              <Button variant="ghost" size="icon" className="h-10 w-10 text-[#374151] hover:text-[#E5E7EB] rounded-2xl">
                <MoreVertical className="w-4 h-4" />
              </Button>
            </div>
          </motion.div>
        ))}
      </div>

      {/* FAB */}
      {isOnline && (
        <div className="fixed bottom-28 right-6 z-40">
           <Button className="w-16 h-16 rounded-[2rem] bg-gradient-to-br from-[#2563EB] to-[#A855F7] shadow-2xl shadow-blue-500/40 text-white flex items-center justify-center active:scale-95 transition-transform duration-100">
             <Plus className="w-8 h-8" />
           </Button>
        </div>
      )}

      {/* Bottom Nav */}
      <div className="fixed bottom-6 left-6 right-6 h-20 bg-[#111827]/90 backdrop-blur-2xl border border-[#374151] rounded-[2.5rem] flex items-center justify-around shadow-2xl z-50">
        <Link to="/" className="p-4 text-[#9CA3AF] hover:text-[#2563EB] transition-colors">
          <Activity className="w-6 h-6" />
        </Link>
        <Link to="/browser" className="p-4 text-[#2563EB]">
          <FolderOpen className="w-6 h-6" />
        </Link>
        <Link to="/activity" className="p-4 text-[#9CA3AF] hover:text-[#2563EB] transition-colors">
          <Clock className="w-6 h-6" />
        </Link>
      </div>

      {/* File Details Overlay */}
      <AnimatePresence>
        {selectedFile && (
          <FileDetails 
            file={selectedFile}
            onClose={() => setSelectedFile(null)}
            onDownload={() => {
              toast.success("Download started!");
              setSelectedFile(null);
            }}
            onShare={() => {
              androidBridge.shareInvite();
              setSelectedFile(null);
            }}
            onDelete={() => {
              toast.error("Delete pending implementation");
              setSelectedFile(null);
            }}
          />
        )}
      </AnimatePresence>
    </div>
  );
}