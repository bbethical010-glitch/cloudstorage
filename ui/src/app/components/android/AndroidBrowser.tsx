import { useNavigate } from "react-router";
import { motion, AnimatePresence } from "motion/react";
import React, { useState, useEffect, useContext, useRef, useCallback } from "react";
import {
  ChevronLeft,
  ChevronRight,
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
  Activity,
  FolderOpen,
  Clock,
  RefreshCw,
  AlertCircle,
  Plus
} from "lucide-react";
import { Link } from "react-router";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Card } from "../ui/card";
import { AppStateContext } from "../../App";
import { toast } from "sonner";
import { FileDetails } from "./FileDetails";
import { androidBridge } from "../../bridge";

export function formatSize(bytes: number) {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
}

export function formatDate(timestamp: number) {
  if (!timestamp) return "Unknown";
  const date = new Date(timestamp);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffMins < 1) return "Just now";
  if (diffMins < 60) return `${diffMins} mins ago`;
  if (diffHours < 24) return `${diffHours} hours ago`;
  if (diffDays === 1) return "Yesterday";
  if (diffDays < 7) return `${diffDays} days ago`;

  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: date.getFullYear() !== now.getFullYear() ? 'numeric' : undefined });
}

function buildEncodedUploadChunkQuery(params: {
  filename: string;
  chunkIndex: number;
  totalChunks: number;
  totalSize: number;
  path?: string;
}) {
  const pairs: Array<[string, string]> = [
    ['filename', params.filename],
    ['chunkIndex', String(params.chunkIndex)],
    ['totalChunks', String(params.totalChunks)],
    ['totalSize', String(params.totalSize)],
  ];

  if (params.path) {
    pairs.push(['path', params.path]);
  }

  return pairs
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&');
}

export function AndroidBrowser() {
  const navigate = useNavigate();
  const ctx = useContext(AppStateContext);
  const appState = ctx?.state;
  const refreshFiles = ctx?.refreshFiles;
  const [searchQuery, setSearchQuery] = useState("");
  const [showSearch, setShowSearch] = useState(false);
  const [loading, setLoading] = useState(false);
  const [selectedFile, setSelectedFile] = useState<any | null>(null);

  const [currentPath, setCurrentPath] = useState("");
  const [isFabOpen, setIsFabOpen] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<{ fileName: string, progress: number } | null>(null);

  const [showCreateFolder, setShowCreateFolder] = useState(false);
  const [newFolderName, setNewFolderName] = useState("");
  const [folderError, setFolderError] = useState("");

  const fileInputRef = useRef<HTMLInputElement>(null);

  const isOnline = appState?.node?.isRunning ?? false;

  const fetchFiles = useCallback(async (path: string) => {
    console.log("[JS_DEBUG] fetchFiles called for path: " + path + ". Has refreshFiles: ", !!refreshFiles);
    setLoading(true);
    try {
      if (refreshFiles) {
        await refreshFiles(path);
        console.log("[JS_DEBUG] refreshFiles completed");
      }
    } finally {
      setLoading(false);
    }
  }, [refreshFiles]);

  useEffect(() => {
    if (isOnline) {
      fetchFiles(currentPath);
    }
  }, [fetchFiles, isOnline, currentPath]);

  const files = (appState?.files?.items || []).map((f: any) => ({
    ...f,
    type: f.isDirectory ? "Folder" : "File",
    size: f.isDirectory ? "Folder" : formatSize(f.size),
    modified: formatDate(f.lastModified)
  }));

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

  const handleNavigateFolder = (folderName: string) => {
    setCurrentPath(prev => prev ? `${prev}/${folderName}` : folderName);
  };

  const handleUploadFileClick = () => {
    setIsFabOpen(false);
    fileInputRef.current?.click();
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const token = localStorage.getItem('cloud_storage_android_token') || '';
    const CHUNK_SIZE = 1 * 1024 * 1024;
    const totalChunks = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));

    setUploadProgress({ fileName: `Uploading ${file.name}...`, progress: 0 });

    try {
      for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
        const start = chunkIndex * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, file.size);
        const chunk = file.slice(start, end);

        await new Promise<void>((resolve, reject) => {
          const xhr = new XMLHttpRequest();
          const uploadQuery = buildEncodedUploadChunkQuery({
            filename: file.name,
            chunkIndex,
            totalChunks,
            totalSize: file.size,
            path: currentPath || undefined,
          });
          const uploadUrl = `http://127.0.0.1:8080/api/upload_chunk?${uploadQuery}`;
          xhr.open("POST", uploadUrl);
          xhr.setRequestHeader('Authorization', `Bearer ${token}`);
          xhr.setRequestHeader('Content-Type', 'application/octet-stream');

          xhr.upload.addEventListener('progress', (event) => {
            if (event.lengthComputable) {
              const totalUploaded = (chunkIndex * CHUNK_SIZE) + event.loaded;
              setUploadProgress(prev => prev ? { ...prev, progress: Math.round((totalUploaded / file.size) * 100) } : null);
            }
          });

          xhr.onload = () => {
            let payload: any = {};
            try {
              payload = JSON.parse(xhr.responseText || "{}");
            } catch {
              payload = {};
            }

            if (xhr.status === 200 && payload.success !== false) {
              resolve();
            } else {
              reject(new Error(payload.error || `Server error ${xhr.status}`));
            }
          };
          xhr.onerror = () => reject(new Error('Network error'));
          xhr.send(chunk);
        });
      }

      const completeParams = new URLSearchParams({ filename: file.name });
      if (currentPath) completeParams.set("path", currentPath);
      const completeRes = await fetch(`http://127.0.0.1:8080/api/upload_complete?${completeParams.toString()}`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`
        }
      });

      if (!completeRes.ok) {
        throw new Error(`Finalize failed (${completeRes.status})`);
      }

      setUploadProgress(null);
      // toast.success(`Uploaded: ${file.name}`);
      const appSet = JSON.parse(localStorage.getItem('appSettings') || '{}');
      if (appSet.notifications !== 'None') {
        androidBridge.showNotification("Upload Complete", file.name);
      }
      fetchFiles(currentPath);
    } catch (err: any) {
      setUploadProgress(null);
      toast.error(`Upload failed: ${err.message}`);
    }

    // Reset input
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const handleCreateFolderClick = () => {
    setIsFabOpen(false);
    setNewFolderName("");
    setFolderError("");
    setShowCreateFolder(true);
  };

  const submitCreateFolder = async () => {
    const name = newFolderName.trim();
    if (!name) {
      setFolderError("Folder name cannot be empty");
      return;
    }
    if (name.includes("/") || name.includes("\\")) {
      setFolderError("Folder name contains invalid characters");
      return;
    }

    setShowCreateFolder(false);

    try {
      const token = localStorage.getItem('cloud_storage_android_token') || '';
      const actualPath = currentPath === "/" ? "" : currentPath;

      const formData = new URLSearchParams();
      formData.append("name", name);
      formData.append("path", actualPath);

      const res = await fetch(`http://127.0.0.1:8080/api/folder`, {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          "Authorization": `Bearer ${token}`
        },
        body: formData.toString()
      });

      if (!res.ok) throw new Error("Failed to create folder");
      toast.success("Folder created");

      const appSet = JSON.parse(localStorage.getItem('appSettings') || '{}');
      if (appSet.notifications !== 'None') {
        androidBridge.showNotification("Folder Created", name);
      }

      fetchFiles(currentPath);
    } catch (err: any) {
      toast.error(err.message || "Failed to create folder");
    }
  };

  const handleImportDevice = () => {
    setIsFabOpen(false);
    androidBridge.selectFolder();
  };

  const pathParts = currentPath ? currentPath.split('/') : [];

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
              onClick={() => fetchFiles(currentPath)}
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
              className="mb-4 overflow-hidden"
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

        {/* Path Bar Component */}
        <div className="flex items-center gap-2 px-4 py-3 bg-[#111827] border border-[#374151] rounded-2xl mb-2 overflow-x-auto scrollbar-hide">
          <HardDrive
            className="w-4 h-4 text-[#2563EB] shrink-0 cursor-pointer"
            onClick={() => setCurrentPath("")}
          />
          <div className="flex items-center gap-1 text-[11px] font-bold text-[#9CA3AF] whitespace-nowrap">
            <span
              className={`cursor-pointer hover:text-white transition-colors ${!currentPath ? 'text-white' : ''}`}
              onClick={() => setCurrentPath("")}
            >
              {appState?.node?.folderName || "Drive"}
            </span>
            {pathParts.map((part, idx) => (
              <React.Fragment key={idx}>
                <ChevronRight className="w-3 h-3 text-[#4B5563] shrink-0" />
                <span
                  className={`cursor-pointer hover:text-white transition-colors ${idx === pathParts.length - 1 ? 'text-[#2563EB]' : ''}`}
                  onClick={() => {
                    const newPath = pathParts.slice(0, idx + 1).join('/');
                    setCurrentPath(newPath);
                  }}
                >
                  {part}
                </span>
              </React.Fragment>
            ))}
          </div>
        </div>
      </div>

      {/* File List */}
      <div className="px-6 space-y-3">
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

        {isOnline && loading && files.length === 0 && (
          <div className="space-y-3 mt-4">
            {[1, 2, 3, 4, 5].map(i => (
              <div key={i} className="bg-[#111827] border border-[#374151] p-4 flex items-center gap-4 rounded-2xl animate-pulse">
                <div className="w-14 h-14 rounded-2xl bg-[#1F2937]" />
                <div className="flex-1 space-y-2">
                  <div className="h-4 bg-[#1F2937] rounded w-2/3" />
                  <div className="h-3 bg-[#1F2937] rounded w-1/3" />
                </div>
              </div>
            ))}
          </div>
        )}

        {isOnline && filteredFiles.length === 0 && !loading && (
          <div className="py-20 flex flex-col items-center justify-center text-center">
            <div className="w-24 h-24 bg-[#111827] rounded-[2rem] flex items-center justify-center border border-[#374151] mb-6 shadow-inner">
              <FolderOpen className="w-10 h-10 text-[#374151]" />
            </div>
            <h3 className="text-lg font-bold">This folder is empty</h3>
            <p className="text-sm text-[#9CA3AF] mt-2 leading-relaxed">Tap the + button to upload items or<br />create a new folder to organize.</p>
          </div>
        )}

        {isOnline && !loading && filteredFiles.map((file, i) => (
          <motion.div
            key={i}
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.03 }}
            whileTap={{ scale: 0.96 }}
            onClick={() => file.isDirectory ? handleNavigateFolder(file.name) : setSelectedFile(file)}
          >
            <div className="bg-[#111827] border border-[#374151] p-3.5 flex items-center justify-between rounded-2xl active:bg-[#1F2937] transition-colors shadow-sm">
              <div className="flex items-center gap-4 w-full min-w-0">
                <div className="w-14 h-14 shrink-0 rounded-2xl bg-[#0B1220] flex items-center justify-center border border-[#374151]/50 shadow-inner">
                  {getFileIcon(file)}
                </div>
                <div className="min-w-0 flex-1">
                  <h3 className="text-sm font-bold truncate text-[#E5E7EB]">{file.name}</h3>
                  <div className="flex items-center gap-1.5 mt-1">
                    <span className="text-[10px] text-[#9CA3AF] font-medium">{file.size}</span>
                    <span className="w-1 h-1 rounded-full bg-[#4B5563]" />
                    <span className="text-[10px] text-[#9CA3AF] font-medium">{file.modified}</span>
                  </div>
                </div>
                <Button variant="ghost" size="icon" className="shrink-0 h-10 w-10 text-[#4B5563] hover:text-[#E5E7EB] hover:bg-[#1F2937] rounded-xl" onClick={(e) => { e.stopPropagation(); setSelectedFile(file); }}>
                  <MoreVertical className="w-4 h-4" />
                </Button>
              </div>
            </div>
          </motion.div>
        ))}
      </div>

      {/* FAB */}
      {isOnline && (
        <div className="fixed bottom-28 right-6 z-40 flex flex-col items-end">
          <AnimatePresence>
            {isFabOpen && (
              <motion.div
                initial={{ opacity: 0, y: 20, scale: 0.8 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: 20, scale: 0.8 }}
                className="flex flex-col gap-3 mb-4 items-end"
              >
                <Button onClick={handleUploadFileClick} className="h-12 px-5 rounded-full bg-[#111827] border border-[#374151] shadow-xl text-sm font-medium hover:bg-[#1F2937] flex gap-3">
                  Upload File <Upload className="w-4 h-4 text-[#2563EB]" />
                </Button>
                <Button onClick={handleCreateFolderClick} className="h-12 px-5 rounded-full bg-[#111827] border border-[#374151] shadow-xl text-sm font-medium hover:bg-[#1F2937] flex gap-3">
                  Create Folder <FolderPlus className="w-4 h-4 text-[#10B981]" />
                </Button>
              </motion.div>
            )}
          </AnimatePresence>

          <Button
            onClick={() => setIsFabOpen(!isFabOpen)}
            className={`w-16 h-16 rounded-[2rem] shadow-2xl shadow-blue-500/30 text-white flex items-center justify-center active:scale-95 transition-all duration-200 ${isFabOpen ? 'bg-[#1F2937] rotate-45' : 'bg-gradient-to-br from-[#2563EB] to-[#A855F7]'}`}
          >
            <Plus className="w-8 h-8" />
          </Button>
        </div>
      )}

      {/* Upload Progress Toast */}
      <AnimatePresence>
        {uploadProgress && (
          <motion.div
            initial={{ opacity: 0, y: 50 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 50, scale: 0.9 }}
            className="fixed bottom-32 left-6 right-6 z-50 p-4 bg-[#111827]/95 backdrop-blur-xl border border-[#374151] rounded-2xl shadow-2xl"
          >
            <div className="flex justify-between items-center mb-3">
              <span className="text-sm font-bold text-white truncate max-w-[200px]">{uploadProgress.fileName}</span>
              <span className="text-xs text-[#2563EB] font-bold">{uploadProgress.progress}%</span>
            </div>
            <div className="h-2 w-full bg-[#0B1220] rounded-full overflow-hidden">
              <motion.div
                className="h-full bg-gradient-to-r from-[#2563EB] to-[#A855F7]"
                initial={{ width: 0 }}
                animate={{ width: `${uploadProgress.progress}%` }}
              />
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Bottom Removed for Full Scroll */}

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
      <input type="file" ref={fileInputRef} onChange={handleFileChange} className="hidden" />

      {/* Create Folder Modal */}
      {showCreateFolder && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
          <Card className="w-full max-w-sm bg-[#111827] border-[#374151] p-6 shadow-2xl">
            <h3 className="text-xl font-bold text-white mb-4">Create Folder</h3>
            <Input
              value={newFolderName}
              onChange={(e) => {
                setNewFolderName(e.target.value);
                setFolderError("");
              }}
              placeholder="Folder name"
              className="bg-[#0B1220] border-[#374151] text-white"
              autoFocus
            />
            {folderError && <p className="text-red-400 text-xs mt-2">{folderError}</p>}
            <div className="flex justify-end gap-3 mt-6">
              <Button variant="ghost" className="text-gray-400 hover:text-white" onClick={() => setShowCreateFolder(false)}>Cancel</Button>
              <Button className="bg-blue-600 hover:bg-blue-700 text-white" onClick={submitCreateFolder}>Create</Button>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
