import { useState, useRef, useEffect } from "react";
import { motion, AnimatePresence } from "motion/react";
import {
  HardDrive,
  FolderOpen,
  Share2,
  Activity,
  Settings,
  Search,
  ChevronRight,
  Folder,
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  Archive,
  Download,
  Trash2,
  MoreVertical,
  ChevronUp,
  ChevronDown,
  Eye,
  Clock,
  Star,
  Info,
  Layers,
  Cloud,
  LayoutGrid,
  List,
  Plus,
  Upload,
  User,
  FileEdit,
  Move,
  Home,
  X,
  Check,
  Menu,
  ArrowLeft,
  Sun,
  Moon
} from "lucide-react";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Progress } from "../ui/progress";
import { Badge } from "../ui/badge";
import { Separator } from "../ui/separator";
import { ScrollArea } from "../ui/scroll-area";
import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";
import { toast } from "sonner";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "../ui/dropdown-menu";

interface FileNode {
  id: string; // Used as the full uri
  name: string;
  path: string; // Added path property
  isDirectory: boolean;
  size: number;
  lastModified: number;
}

export function WebConsole() {
  const [files, setFiles] = useState<FileNode[]>([]);
  const [currentPath, setCurrentPath] = useState<string>("");
  const [selectedFile, setSelectedFile] = useState<FileNode | null>(null);
  const [activeTab, setActiveTab] = useState("Drive");
  const [viewMode, setViewMode] = useState<"list" | "grid">("list");
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [folderProgress, setFolderProgress] = useState({ success: 0, uploading: 0, failed: 0, total: 0 });
  const [failedUploads, setFailedUploads] = useState<{file: File, error: string}[]>([]);
  const [sanitizedUploads, setSanitizedUploads] = useState<Record<string, string>>({});
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [storageStats, setStorageStats] = useState({ total: 0, used: 0, free: 0 });
  const [searchQuery, setSearchQuery] = useState("");
  const [sortField, setSortField] = useState<"name" | "size" | "lastModified" | "type">("name");
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("asc");
  const [selectedFiles, setSelectedFiles] = useState<Set<string>>(new Set());
  const [lastSelectedIndex, setLastSelectedIndex] = useState<number | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [isNodeOffline, setIsNodeOffline] = useState(false);
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const [showShareModal, setShowShareModal] = useState(false);
  const [shareConfig, setShareConfig] = useState({ expiry: '24h', readOnly: true });

  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [authMode, setAuthMode] = useState<'login' | 'signup' | 'none'>('none');
  const [authEmail, setAuthEmail] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authError, setAuthError] = useState('');
  
  const [theme, setTheme] = useState<"dark" | "light">(() => {
    return (localStorage.getItem("cloud_storage_theme") as "dark" | "light") || "dark";
  });

  useEffect(() => {
    const root = document.documentElement;
    if (theme === "light") {
      root.classList.add("light");
    } else {
      root.classList.remove("light");
    }
    localStorage.setItem("cloud_storage_theme", theme);
  }, [theme]);
  
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!getBaseUrl()) return;
    const checkStatus = async () => {
      try {
        const authStat = await fetch(`${getBaseUrl()}/api/auth/status`);
        if (authStat.ok) {
           const { hasAccount } = await authStat.json();
           const token = localStorage.getItem('cloud_storage_token');
           const params = new URLSearchParams(window.location.hash.split('?')[1]);
           const pwd = params.get('pwd');
           
           if (token || pwd) {
               const verify = await fetch(`${getBaseUrl()}/api/storage`, { headers: { Authorization: `Bearer ${pwd || token}` }});
               if (verify.ok) {
                   setIsAuthenticated(true);
                   setIsNodeOffline(false);
                   setAuthMode('none');
                   return;
               }
           }
           
           setIsAuthenticated(false);
           setAuthMode(hasAccount ? 'login' : 'signup');
           setIsNodeOffline(false);
        } else if (authStat.status === 502 || authStat.status === 503 || authStat.status === 404) {
           setIsNodeOffline(true);
        }
      } catch (e) {
        setIsNodeOffline(true);
      }
    };
    checkStatus();
    const interval = setInterval(checkStatus, 20000);
    return () => clearInterval(interval);
  }, []);

  const SidebarContent = () => (
    <div className="flex flex-col h-full p-4">
      <div className="mb-8 space-y-2">
         <Button 
          onClick={() => { fileInputRef.current?.click(); setIsMobileMenuOpen(false); }}
          className="w-full bg-[#2563EB] hover:bg-[#1d4ed8] h-11 rounded-xl shadow-lg shadow-blue-500/10 gap-2 font-bold transition-all"
         >
           <Upload className="w-5 h-5" /> Upload File
         </Button>
         <Button 
          onClick={() => { folderInputRef.current?.click(); setIsMobileMenuOpen(false); }}
          className="w-full bg-[#2563EB] hover:bg-[#1d4ed8] h-11 rounded-xl shadow-lg shadow-blue-500/10 gap-2 font-bold transition-all"
         >
           <Folder className="w-5 h-5 fill-white/20" /> Upload Folder
         </Button>
         <Button 
          variant="outline"
          onClick={() => { handleCreateFolder(); setIsMobileMenuOpen(false); }}
          className="w-full bg-transparent border-[#1F2937] hover:bg-[#111827] h-10 rounded-xl gap-2 font-bold transition-all"
         >
           <Plus className="w-4 h-4" /> New Folder
         </Button>
         <input type="file" ref={fileInputRef} className="hidden" multiple onChange={handleUpload} />
         <input type="file" ref={folderInputRef} className="hidden" multiple {...{webkitdirectory: "true", directory: "true"} as any} onChange={handleUpload} />
      </div>

      <ScrollArea className="flex-1 -mx-2 px-2">
        <div className="space-y-6">
          <div className="space-y-1">
            <h4 className="text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] px-3 mb-3">Main</h4>
            {[
              { label: "Drive", icon: HardDrive },
              { label: "Recent", icon: Clock },
              { label: "Shared", icon: Share2 },
              { label: "Trash", icon: Trash2 },
            ].map((item, i) => (
              <button 
                key={i}
                onClick={() => { setActiveTab(item.label); setCurrentPath(""); setIsMobileMenuOpen(false); }}
                className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all ${
                  activeTab === item.label ? 'bg-[#2563EB]/10 text-[#2563EB]' : 'text-[#9CA3AF] hover:bg-[#111827] hover:text-[#E5E7EB]'
                }`}
              >
                <item.icon className="w-4 h-4" />
                {item.label}
              </button>
            ))}
          </div>

          <div className="pt-6 border-t border-[#1F2937]">
            <h4 className="text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] px-3 mb-3">Drive Health</h4>
            <div className="px-3 space-y-4">
              <div className="space-y-2">
                 <div className="flex justify-between text-[10px] font-bold uppercase tracking-wider text-[#9CA3AF]">
                  <span>{storageStats.total > 0 ? `${formatSize(storageStats.used)} / ${formatSize(storageStats.total)}` : "Analyzing..."}</span>
                  <span className="text-[#E5E7EB]">{storageStats.total > 0 ? Math.round((storageStats.used / storageStats.total) * 100) : 0}%</span>
                </div>
                <Progress value={storageStats.total > 0 ? (storageStats.used / storageStats.total) * 100 : 0} className="h-1.5 bg-[#1F2937]" />
              </div>
            </div>
          </div>
        </div>
      </ScrollArea>
    </div>
  );

  const toggleSort = (field: "name" | "size" | "lastModified" | "type") => {
    if (sortField === field) setSortDirection(prev => prev === "asc" ? "desc" : "asc");
    else { setSortField(field); setSortDirection("asc"); }
  };

  const filteredAndSortedFiles = files
    .filter(f => f.name.toLowerCase().includes(searchQuery.toLowerCase()))
    .sort((a, b) => {
      // folders first
      if (a.isDirectory && !b.isDirectory) return -1;
      if (!a.isDirectory && b.isDirectory) return 1;

      let val = 0;
      switch (sortField) {
        case "name": val = a.name.localeCompare(b.name); break;
        case "size": val = (a.size || 0) - (b.size || 0); break;
        case "lastModified": val = (a.lastModified || 0) - (b.lastModified || 0); break;
        case "type":
          const getExt = (n: string) => n.split('.').pop()?.toLowerCase() || '';
          val = getExt(a.name).localeCompare(getExt(b.name));
          break;
      }
      return sortDirection === "asc" ? val : -val;
    });

  const getBaseUrl = () => {
    const path = window.location.pathname;
    if (path.includes('/node/')) {
      const segments = path.split('/');
      const nodeIdx = segments.indexOf('node');
      if (nodeIdx !== -1 && segments.length > nodeIdx + 1) {
        return segments.slice(0, nodeIdx + 2).join('/');
      }
    }
    return '';
  };

  const getHeaders = () => {
    const token = localStorage.getItem('cloud_storage_token') || '';
    const params = new URLSearchParams(window.location.hash.split('?')[1]);
    const pwd = params.get('pwd') || token;
    return { 'Authorization': `Bearer ${pwd}` };
  };

  useEffect(() => {
    loadFiles(currentPath);
    loadStorageStats();
  }, [currentPath, activeTab]);

  useEffect(() => {
    let interval: any;
    const checkStatus = async () => {
      try {
        const res = await fetch(`${getBaseUrl()}/api/status`);
        if (!res.ok) throw new Error();
        const data = await res.json();
        setIsNodeOffline(data.status === "offline");
      } catch {
        setIsNodeOffline(true);
      }
    };
    checkStatus();
    interval = setInterval(checkStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  const PreviewContent = () => (
    <AnimatePresence mode="wait">
      {selectedFile ? (
        <motion.div 
          key={selectedFile.id}
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: 20 }}
          className="h-full flex flex-col p-6 w-full max-h-screen"
        >
          <div className="flex items-center justify-between mb-8 shrink-0">
             <Button variant="ghost" size="icon" className="h-8 w-8 text-[#E5E7EB] md:hidden" onClick={() => setSelectedFile(null)}>
               <ArrowLeft className="w-5 h-5 text-white" />
             </Button>
             <h3 className="font-bold text-sm tracking-widest uppercase text-[#4B5563] hidden md:block">Details</h3>
             <div className="flex-1 md:hidden" />
             <Button variant="ghost" size="icon" className="h-8 w-8 text-[#4B5563]" onClick={() => setSelectedFile(null)}>
               <ChevronUp className="w-4 h-4 hidden md:block" />
             </Button>
          </div>

          <div className="aspect-square shrink-0 w-full min-h-[180px] bg-gradient-to-br from-[#111827] to-[#0B1220] rounded-[2rem] border border-[#1F2937] flex items-center justify-center mb-8 shadow-2xl relative overflow-hidden group">
            <div className="absolute inset-0 bg-[#2563EB]/5 opacity-0 group-hover:opacity-100 transition-opacity" />
            {(() => {
              const ext = selectedFile.name.split('.').pop()?.toLowerCase() || '';
              const pwd = new URLSearchParams(window.location.hash.split('?')[1]).get('pwd') || '';
              const url = `${getBaseUrl()}/api/download?path=${encodeURIComponent(currentPath)}&file=${encodeURIComponent(selectedFile.name)}&pwd=${encodeURIComponent(pwd)}`;
              
              if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) {
                return (
                  <div className="w-full h-full relative flex items-center justify-center">
                    <img 
                      src={url} 
                      className="object-contain w-full h-full absolute inset-0 text-transparent" 
                      alt="Preview"
                      onLoad={() => console.log("PREVIEW_DEBUG", "Image loaded:", url)}
                      onError={(e) => {
                        console.error("PREVIEW_ERROR", selectedFile, "Image broken.", e);
                        e.currentTarget.style.display = 'none';
                        e.currentTarget.parentElement?.insertAdjacentHTML('beforeend', '<p class="text-[10px] text-red-400 p-4 text-center z-10 w-full relative">Preview not available.</p>');
                      }}
                    />
                  </div>
                );
              }
              if (['mp4', 'webm', 'mov', 'avi'].includes(ext)) {
                return (
                  <div className="w-full h-full relative flex items-center justify-center bg-black/50">
                    <video 
                      src={url} 
                      controls 
                      preload="metadata"
                      className="object-contain w-full h-full absolute inset-0 text-transparent" 
                      onLoadedData={() => console.log("PREVIEW_DEBUG", "Video loaded:", url)}
                      onError={(e) => {
                        console.error("PREVIEW_ERROR", selectedFile, "Video broken.", e);
                        e.currentTarget.style.display = 'none';
                        e.currentTarget.parentElement?.insertAdjacentHTML('beforeend', '<p class="text-[10px] text-red-400 p-4 text-center z-10 w-full relative">Preview not available.</p>');
                      }}
                    />
                  </div>
                );
              }
              if (ext === 'pdf') {
                return <iframe src={url} className="w-full h-full bg-white rounded-2xl" title="PDF Preview" />;
              }
              return getFileIcon(selectedFile.name, selectedFile.isDirectory, "w-20 h-20 transition-transform group-hover:scale-110 duration-500 z-10 relative");
            })()}
            
            <div className="absolute bottom-4 left-4 right-4 pointer-events-none">
               <div className="bg-[#0B1220]/80 backdrop-blur-md p-3 rounded-2xl border border-[#374151]/30 opacity-0 group-hover:opacity-100 translate-y-4 group-hover:translate-y-0 transition-all">
                  <p className="text-[10px] text-center font-bold text-[#2563EB] uppercase tracking-widest">
                      {selectedFile.isDirectory ? "Folder" : "File"}
                  </p>
               </div>
            </div>
          </div>
          
          <div className="flex-1 overflow-y-auto space-y-8 pr-2">
            <div>
              <h3 className="text-xl font-bold break-words pr-4 text-white leading-tight">{selectedFile.name}</h3>
              <div className="flex items-center gap-2 mt-2">
                 <Badge className="bg-[#2563EB]/10 text-[#2563EB] border-transparent text-[10px] uppercase font-bold tracking-widest">
                     {selectedFile.isDirectory ? "Folder" : selectedFile.name.split('.').pop() || 'Unknown'}
                 </Badge>
                 <span className="text-[10px] text-[#4B5563] font-mono uppercase tracking-widest">{formatSize(selectedFile.size)}</span>
              </div>
            </div>

            <div className="space-y-5 pt-6 border-t border-[#1F2937]/50">
              <div className="grid grid-cols-2 gap-4">
                 <div className="space-y-1">
                    <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">Modified</label>
                    <p className="text-xs font-mono text-[#E5E7EB]">{formatDate(selectedFile.lastModified)}</p>
                 </div>
              </div>
              <div className="space-y-2">
                <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">Location</label>
                <div className="flex items-center gap-2 px-3 py-2 bg-[#111827] border border-[#1F2937] rounded-xl flex-wrap">
                  <HardDrive className="w-3.5 h-3.5 text-[#2563EB] shrink-0" />
                  <p className="text-[10px] font-mono text-[#9CA3AF] break-all">/Storage/{currentPath}</p>
                </div>
              </div>
            </div>

            <div className="pt-8 space-y-3 pb-4">
              <Button 
                className="w-full bg-[#2563EB] hover:bg-[#1d4ed8] h-11 rounded-xl gap-2 font-bold shadow-lg shadow-blue-500/10 active:scale-95 transition-all outline-none"
                onClick={() => handleDownload(selectedFile)}
              >
                <Download className="w-4 h-4" /> Download
              </Button>
              <div className="grid grid-cols-2 gap-3">
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs" onClick={() => handleRename(selectedFile)}>
                   <FileEdit className="w-3.5 h-3.5" /> Rename
                 </Button>
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs" onClick={() => setShowShareModal(true)}>
                   <Share2 className="w-3.5 h-3.5" /> Share
                 </Button>
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs" onClick={() => {
                    const dest = prompt("Enter destination folder path (e.g. Documents):");
                    if (dest !== null) {
                       setSelectedFiles(new Set([selectedFile.id]));
                       handleBulkAction('move', dest);
                    }
                 }}>
                   <Move className="w-3.5 h-3.5" /> Move
                 </Button>
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs text-[#EF4444]" onClick={() => handleDelete(selectedFile)}>
                   <Trash2 className="w-3.5 h-3.5" /> Delete
                 </Button>
              </div>
            </div>
          </div>
        </motion.div>
      ) : (
        <div className="h-full flex flex-col items-center justify-center text-[#4B5563] p-12 text-center md:flex hidden">
          <div className="w-20 h-20 bg-[#111827] rounded-[2.5rem] border border-[#1F2937] flex items-center justify-center mb-6">
            <Eye className="w-8 h-8 opacity-20" />
          </div>
          <h3 className="text-sm font-bold uppercase tracking-widest text-[#E5E7EB]">No Preview</h3>
          <p className="text-xs mt-3 leading-relaxed">Select a file to view its preview and details</p>
        </div>
      )}
    </AnimatePresence>
  );

  const loadStorageStats = async () => {
    try {
      const res = await fetch(`${getBaseUrl()}/api/storage`, { headers: getHeaders(), cache: "no-store" });
      if (res.ok) setStorageStats(await res.json());
    } catch {}
  };

  const clearSelection = () => {
    setSelectedFiles(new Set());
    setLastSelectedIndex(null);
  };

  const toggleSelection = (e: React.MouseEvent, fileId: string, index: number) => {
    e.stopPropagation();
    const newSelection = new Set(selectedFiles);
    if (e.shiftKey && lastSelectedIndex !== null) {
      const start = Math.min(index, lastSelectedIndex);
      const end = Math.max(index, lastSelectedIndex);
      for (let i = start; i <= end; i++) newSelection.add(filteredAndSortedFiles[i].id);
    } else {
      if (newSelection.has(fileId)) newSelection.delete(fileId);
      else newSelection.add(fileId);
    }
    setSelectedFiles(newSelection);
    setLastSelectedIndex(index);
  };

  const loadFiles = async (path: string) => {
    setIsRefreshing(true);
    setSelectedFile(null);
    clearSelection();
    try {
      const timestamp = Date.now();
      const endpoint = activeTab === "Trash" ? `/api/trash?t=${timestamp}` : `/api/files?path=${encodeURIComponent(path)}&t=${timestamp}`;
      const res = await fetch(`${getBaseUrl()}${endpoint}`, { 
        headers: { ...getHeaders(), 'Cache-Control': 'no-cache, no-store, must-revalidate', 'Pragma': 'no-cache' } 
      });

      if (res.status === 502 || res.status === 503) {
        const body = await res.json().catch(() => ({}));
        const reason = body.error === 'agent_offline' 
            ? 'Android Node is offline. Open the Easy Storage app on your phone.'
            : 'Relay server is unavailable. Please try again in a moment.';
        toast.error(reason, { duration: 6000 });
        setFiles([]);
        return;
      }

      if (res.status === 401) {
        toast.error("Unauthorized: Please provide a valid ?pwd= password.");
        setFiles([]);
        return;
      }
      if (!res.ok) throw new Error("Failed to load files");
      const data = await res.json();
      setFiles(data);
    } catch (e: any) {
      toast.error(e.message || "Failed to load directory");
    } finally {
      setIsRefreshing(false);
      loadStorageStats();
    }
  };

  const getFileIcon = (fileName: string, isDirectory: boolean, className = "w-4 h-4") => {
    if (isDirectory) return <Folder className={`${className} text-[#2563EB] fill-[#2563EB]/10`} />;
    const ext = fileName.split('.').pop()?.toLowerCase() || '';
    if (['png', 'jpg', 'jpeg', 'gif', 'svg'].includes(ext)) return <ImageIcon className={`${className} text-[#A855F7]`} />;
    if (['mp4', 'mov', 'avi'].includes(ext)) return <Film className={`${className} text-[#F59E0B]`} />;
    if (['mp3', 'wav'].includes(ext)) return <Music className={`${className} text-[#EC4899]`} />;
    if (['zip', 'rar', 'tar', 'gz'].includes(ext)) return <Archive className={`${className} text-[#6366F1]`} />;
    return <FileText className={`${className} text-[#9CA3AF]`} />;
  };

  const formatSize = (bytes: number | undefined) => {
    if (bytes === undefined || bytes === null || bytes === 0) return "--";
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${(bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1)} ${sizes[i]}`;
  };

  const formatDate = (ms: number | undefined) => {
    if (!ms) return "--";
    return new Date(ms).toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
  };

  const handleCreateFolder = async () => {
    const name = prompt("Folder Name:");
    if (!name) return;
    const formData = new URLSearchParams();
    formData.append("path", currentPath);
    formData.append("name", name);
    try {
      const res = await fetch(`${getBaseUrl()}/api/folder`, {
        method: 'POST',
        headers: { ...getHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formData
      });
      if (res.ok) {
        toast.success("Folder created!");
        loadFiles(currentPath);
      } else {
        toast.error("Failed to create folder");
      }
    } catch {
      toast.error("Network error");
    }
  };

  const traverseFileTree = async (item: any, path: string = ''): Promise<File[]> => {
    return new Promise((resolve) => {
      if (item.isFile) {
        item.file((file: File) => {
          Object.defineProperty(file, 'webkitRelativePath', {
            value: path + file.name,
            writable: true
          });
          resolve([file]);
        });
      } else if (item.isDirectory) {
        const dirReader = item.createReader();
        const entries: any[] = [];
        
        const readEntries = () => {
          dirReader.readEntries(async (results: any[]) => {
            if (!results.length) {
              const filesPromises = entries.map(entry => traverseFileTree(entry, path + item.name + '/'));
              const filesArrays = await Promise.all(filesPromises);
              resolve(filesArrays.flat());
            } else {
              entries.push(...results);
              readEntries();
            }
          });
        };
        readEntries();
      } else {
        resolve([]);
      }
    });
  };

  const processFiles = async (files: File[]) => {
    if (!files || files.length === 0) return;
    
    setIsUploading(true);
    setUploadProgress(0);
    setFolderProgress({ success: 0, uploading: files.length, failed: 0, total: files.length });
    setFailedUploads([]);
    setSanitizedUploads({});

    // Pre-flight check
    try {
      const statusRes = await fetch(`${getBaseUrl()}/api/status`, {
        method: 'GET',
        headers: getHeaders()
      });
      if (!statusRes.ok) {
        throw new Error();
      }
    } catch {
      setIsUploading(false);
      toast.error("Android Node is offline. Please open the Easy Storage app on your phone and ensure it is running.");
      return;
    }

    const manifest = files.filter(f => f.webkitRelativePath && f.webkitRelativePath.includes('/')).map(f => {
      const CHUNK_SIZE = 5 * 1024 * 1024;
      return {
        relativePath: f.webkitRelativePath, // Use verbatim relativePath
        fileId: crypto.randomUUID(),
        size: f.size,
        totalChunks: Math.max(1, Math.ceil(f.size / CHUNK_SIZE))
      };
    });

    if (manifest.length > 0) {
      try {
        const res = await fetch(`${getBaseUrl()}/api/folder_manifest?path=${encodeURIComponent(currentPath)}`, {
          method: 'POST',
          headers: { ...getHeaders(), 'Content-Type': 'application/json' },
          body: JSON.stringify(manifest)
        });
        if (!res.ok) {
          const errData = await res.json().catch(() => ({}));
          throw new Error(errData.error || "Failed to pre-create directory tree");
        }
      } catch (e: any) {
        setIsUploading(false);
        toast.error(`Folder manifest creation failed: ${e.message}`);
        return;
      }
    }

    const failed: {file: File, error: string}[] = [];

    for (let i = 0; i < files.length; i++) {
        const file = files[i] as File & { webkitRelativePath?: string };
        let relativePath = file.webkitRelativePath || "";
        let filename = file.name;
        
        if (relativePath) {
            const parts = relativePath.split('/');
            filename = parts.pop() || file.name;
        }
        
        let success = false;
        let lastError = "";
        
        for (let retry = 0; retry < 3; retry++) {
            try {
                const CHUNK_SIZE = 5 * 1024 * 1024;
                const totalChunks = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));
                setUploadProgress(0);

                for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                    const start = chunkIndex * CHUNK_SIZE;
                    const end = Math.min(start + CHUNK_SIZE, file.size);
                    const chunk = file.slice(start, end);

                    await new Promise((resolve, reject) => {
                        const xhr = new XMLHttpRequest();
                        let uploadUrl = `${getBaseUrl()}/api/upload_chunk?path=${encodeURIComponent(currentPath)}&filename=${encodeURIComponent(filename)}&chunkIndex=${chunkIndex}&totalChunks=${totalChunks}`;
                        if (file.webkitRelativePath) {
                            uploadUrl += `&relativePath=${encodeURIComponent(file.webkitRelativePath)}`;
                        }
                        
                        console.log(`[CHUNK DIAGNOSTIC] fileId=${file.name}, chunkIndex=${chunkIndex}, relativePath=${file.webkitRelativePath}`);
                        
                        xhr.open("POST", uploadUrl);
                        const headers = getHeaders();
                        if (headers.Authorization) xhr.setRequestHeader('Authorization', headers.Authorization);
                        xhr.setRequestHeader("Content-Type", "application/octet-stream");

                        xhr.upload.addEventListener("progress", (event) => {
                            if (event.lengthComputable) {
                                const totalUploadedSoFar = (chunkIndex * CHUNK_SIZE) + event.loaded;
                                setUploadProgress(Math.round((totalUploadedSoFar / file.size) * 100));
                            }
                        });

                        xhr.onload = () => {
                            if (xhr.status === 200) {
                                resolve(null);
                            } else if (xhr.status === 502 || xhr.status === 503) {
                                const bodyStr = xhr.responseText || "{}";
                                let reason = 'Relay server is unavailable. Please try again in a moment.';
                                try {
                                    const body = JSON.parse(bodyStr);
                                    if (body.error === 'agent_offline') {
                                        reason = 'Android Node is offline. Open the Easy Storage app on your phone.';
                                    }
                                } catch(e) {}
                                reject(new Error(reason));
                            } else {
                                try {
                                    const json = JSON.parse(xhr.responseText);
                                    console.error(`Chunk failed for ${file.webkitRelativePath} chunk ${chunkIndex}:`, json.error);
                                    reject(new Error(json.error || `Server error ${xhr.status}`));
                                } catch {
                                    reject(new Error(xhr.responseText || `Server error ${xhr.status}`));
                                }
                            }
                        };
                        xhr.onerror = () => reject(new Error("Network connection severed."));
                        xhr.send(chunk);
                    });
                }
                success = true;
                break;
            } catch (err: any) {
                lastError = err.message;
            }
        }

        if (!success) {
            failed.push({file, error: lastError});
            setFolderProgress(prev => ({ ...prev, uploading: prev.uploading - 1, failed: prev.failed + 1 }));
        } else {
            setFolderProgress(prev => ({ ...prev, uploading: prev.uploading - 1, success: prev.success + 1 }));
        }
    }
    
    if (manifest.length > 0 && files.length > 0) {
        const firstRel = files.find(f => f.webkitRelativePath)?.webkitRelativePath || "";
        const rootFolder = firstRel.split('/')[0] || "folder";
        try {
            const response = await fetch(`${getBaseUrl()}/api/folder_complete?path=${encodeURIComponent(currentPath)}&folder=${encodeURIComponent(rootFolder)}`, {
                method: 'POST',
                headers: getHeaders()
            });
            const data = await response.json();
            if (data.sanitizedNames && Object.keys(data.sanitizedNames).length > 0) {
                setSanitizedUploads(data.sanitizedNames);
                toast.success(`${Object.keys(data.sanitizedNames).length} files were renamed to remove unsupported characters. See console.`, { duration: 8000 });
                console.warn("Sanitized names map:", data.sanitizedNames);
            }
        } catch (e) {
            console.error("folder_complete failed", e);
        }
    }
    
    setIsUploading(false);
    setUploadProgress(100);
    
    if (failed.length > 0) {
        setFailedUploads(failed);
        toast.error(`${failed.length} files failed to upload. See details.`);
    } else {
        toast.success("Upload complete!");
    }
    
    if (fileInputRef.current) fileInputRef.current.value = "";
    if (folderInputRef.current) folderInputRef.current.value = "";
    loadFiles(currentPath);
  };

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      processFiles(Array.from(e.target.files));
    }
  };

  const handleDownload = (file: FileNode) => {
    const url = file.isDirectory 
        ? `${getBaseUrl()}/api/download_folder?path=${encodeURIComponent(file.path)}&folder=${encodeURIComponent(file.name)}`
        : `${getBaseUrl()}/api/download?path=${encodeURIComponent(file.path)}&file=${encodeURIComponent(file.name)}`;
    
    // Create an invisible link to trigger the download natively
    const a = document.createElement('a');
    a.href = url;
    a.download = file.name + (file.isDirectory ? ".zip" : "");
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    toast.info("Download started");
  };

  const handleDelete = async (file: FileNode) => {
    const formData = new URLSearchParams();
    formData.append("path", currentPath);
    formData.append("name", file.name);
    try {
      const res = await fetch(`${getBaseUrl()}/api/delete`, {
        method: 'POST',
        headers: { ...getHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formData
      });
      if (res.ok) {
        toast.success("Moved to Trash");
        if (selectedFile?.id === file.id) setSelectedFile(null);
        loadFiles(currentPath);
      } else {
        toast.error("Failed to delete");
      }
    } catch {
      toast.error("Network error");
    }
  };

  const handleBulkAction = async (action: 'delete' | 'move', destPath = '') => {
    const items = filteredAndSortedFiles
      .filter(f => selectedFiles.has(f.id))
      .map(f => ({ path: currentPath, name: f.name }));
      
    try {
      const res = await fetch(`${getBaseUrl()}/api/bulk_action`, {
        method: 'POST',
        headers: { ...getHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, destinationPath: destPath, items })
      });
      if (res.ok) {
        toast.success(`Items ${action === 'delete' ? 'deleted' : 'moved'}!`);
        clearSelection();
        loadFiles(currentPath);
      } else {
        toast.error(`Bulk ${action} failed`);
      }
    } catch {
      toast.error('Network error');
    }
  };

  const handleBulkDelete = () => {
    if (confirm(`Delete ${selectedFiles.size} items?`)) handleBulkAction('delete');
  };

  const handleBulkMove = () => {
    const dest = prompt("Enter destination folder path (e.g. Documents):");
    if (dest !== null) handleBulkAction('move', dest);
  };

  const handleBulkDownload = () => {
    const items = filteredAndSortedFiles
      .filter(f => selectedFiles.has(f.id))
      .map(f => ({ path: currentPath, name: f.name }));
      
    const url = `${getBaseUrl()}/api/download_bulk?items=${encodeURIComponent(JSON.stringify(items))}`;
    
    const a = document.createElement('a');
    a.href = url;
    a.download = `archive-${Date.now()}.zip`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    toast.info("Bulk download started");
    clearSelection();
  };

  const handleRename = async (file: FileNode) => {
    const newName = prompt("Enter new filename:", file.name);
    if (!newName || newName === file.name) return;
    const formData = new URLSearchParams();
    formData.append("path", currentPath);
    formData.append("oldName", file.name);
    formData.append("newName", newName);
    try {
      const res = await fetch(`${getBaseUrl()}/api/rename`, {
        method: 'POST',
        headers: { ...getHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
        body: formData
      });
      if (res.ok) {
        toast.success("File renamed");
        if (selectedFile?.id === file.id) setSelectedFile({...file, name: newName});
        loadFiles(currentPath);
      } else {
        toast.error("Failed to rename");
      }
    } catch {
      toast.error("Network error");
    }
  };

  const navigateTo = (itemPath: string) => {
    setCurrentPath(itemPath);
  };

  const navigateUp = () => {
    if (!currentPath) return;
    const segments = currentPath.split('/');
    segments.pop();
    setCurrentPath(segments.join('/'));
  };

  if (isNodeOffline) {
    return (
      <div className="h-screen bg-[#0B1220] flex flex-col items-center justify-center text-center p-6 text-[#E5E7EB]">
         <Cloud className="w-24 h-24 mb-6 opacity-20 text-red-500 animate-pulse" />
         <h1 className="text-4xl font-bold mb-4">Node is offline</h1>
         <p className="text-lg text-[#9CA3AF]">Start the node from your device to access files.</p>
      </div>
    );
  }

  const handleAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError('');
    try {
      const endpoint = authMode === 'signup' ? '/api/auth/signup' : '/api/auth/login';
      const res = await fetch(`${getBaseUrl()}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: authEmail, password: authPassword })
      });
      
      const data = await res.json();
      if (res.ok && data.token) {
        localStorage.setItem('cloud_storage_token', data.token);
        setIsAuthenticated(true);
        setAuthMode('none');
        loadFiles("");
        loadStorageStats();
        toast.success(authMode === 'signup' ? "Account crafted & secured!" : "Welcome back");
      } else {
        setAuthError(data.error || "Authentication failed");
      }
    } catch {
      setAuthError("Network error. Is the node online?");
    }
  };

  const handleLogout = async () => {
    try {
      await fetch(`${getBaseUrl()}/api/auth/logout`, { method: 'POST', headers: getHeaders() });
    } catch (e) {}
    localStorage.removeItem('cloud_storage_token');
    setIsAuthenticated(false);
    setAuthMode('login');
    setFiles([]);
  };

  if (!isAuthenticated && authMode !== 'none') {
    return (
      <div className="h-screen bg-[#0B1220] flex flex-col items-center justify-center p-6 text-[#E5E7EB] relative overflow-hidden">
        {/* Decorative Gradients */}
        <div className="absolute top-[-20%] left-[-10%] w-[50%] h-[50%] bg-[#2563EB]/20 blur-[120px] rounded-full pointer-events-none" />
        <div className="absolute bottom-[-20%] right-[-10%] w-[50%] h-[50%] bg-[#A855F7]/20 blur-[120px] rounded-full pointer-events-none" />
        
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} className="z-10 w-full max-w-md">
          <Card className="bg-[#111827]/80 backdrop-blur-xl border-[#1F2937] p-8 shadow-2xl rounded-3xl">
            <div className="flex justify-center mb-6">
              <div className="w-16 h-16 bg-gradient-to-br from-[#2563EB] to-[#A855F7] rounded-2xl flex items-center justify-center shadow-lg shadow-blue-500/20">
                <Cloud className="w-8 h-8 text-white" />
              </div>
            </div>
            <h2 className="text-3xl font-bold text-center text-white mb-2">
              {authMode === 'signup' ? 'Claim Your Node' : 'Node Locked'}
            </h2>
            <p className="text-center text-[#9CA3AF] mb-8 text-sm">
              {authMode === 'signup' 
                ? 'Register credentials directly onto your physical Android device to secure this bridge.' 
                : 'Authenticate to access your synchronized files.'}
            </p>

            <form onSubmit={handleAuth} className="space-y-4">
              <div className="space-y-1">
                <label className="text-xs font-bold text-[#9CA3AF] uppercase px-1">Email</label>
                <Input 
                  type="email" required
                  placeholder="admin@local.host"
                  value={authEmail} onChange={e => setAuthEmail(e.target.value)}
                  className="bg-[#0B1220] border-[#374151] text-white focus:ring-[#2563EB] h-12 rounded-xl"
                />
              </div>
              <div className="space-y-1">
                <label className="text-xs font-bold text-[#9CA3AF] uppercase px-1">Passkey</label>
                <Input 
                  type="password" required
                  placeholder="••••••••"
                  value={authPassword} onChange={e => setAuthPassword(e.target.value)}
                  className="bg-[#0B1220] border-[#374151] text-white focus:ring-[#2563EB] h-12 rounded-xl"
                />
              </div>

              {authError && <div className="text-red-400 text-xs font-medium text-center bg-red-500/10 py-2 rounded-lg">{authError}</div>}

              <Button type="submit" className="w-full h-12 bg-[#2563EB] hover:bg-[#1d4ed8] text-white font-bold rounded-xl mt-4">
                {authMode === 'signup' ? 'Secure Node Bridge' : 'Unlock Node Bridge'}
              </Button>
            </form>
          </Card>
        </motion.div>
      </div>
    );
  }

  return (
    <div className="h-screen bg-[#0B1220] text-[#E5E7EB] flex flex-col overflow-hidden font-sans selection:bg-[#2563EB]/30">
      {/* Top Bar - Refined */}
      <div className="h-14 border-b border-[#1F2937] flex items-center justify-between px-4 md:px-6 bg-[#0B1220] shrink-0 z-20">
        <div className="flex items-center gap-3 md:gap-6 min-w-0">
          <Button variant="ghost" size="icon" className="md:hidden text-[#E5E7EB] shrink-0" onClick={() => setIsMobileMenuOpen(true)}>
            <Menu className="w-5 h-5" />
          </Button>
          <div className="flex items-center gap-3 group cursor-pointer shrink-0" onClick={() => { setActiveTab("Drive"); setCurrentPath(""); }}>
            <div className="hidden md:flex w-8 h-8 bg-gradient-to-br from-[#2563EB] to-[#A855F7] rounded-lg items-center justify-center shadow-lg shadow-blue-500/10">
              <Cloud className="w-5 h-5 text-white" />
            </div>
            <span className="font-bold tracking-tight text-lg hidden md:block">Easy Storage</span>
            {isNodeOffline && (
               <div className="hidden md:flex items-center gap-1.5 px-2 py-1 bg-red-500/10 border border-red-500/20 rounded-full ml-1 shrink-0">
                  <div className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                  <span className="text-[10px] font-bold text-red-500 uppercase tracking-wider">Node Offline</span>
               </div>
            )}
          </div>
          
          <Separator orientation="vertical" className="h-6 bg-[#1F2937] hidden md:block" />
          
          <div className="flex items-center gap-2 text-xs font-medium text-[#9CA3AF] min-w-0 flex-1 overflow-x-auto no-scrollbar whitespace-nowrap mask-gradient-right pr-4">
            <Layers className="w-3.5 h-3.5 shrink-0" />
            <span className="cursor-pointer hover:text-white transition-colors shrink-0" onClick={() => setCurrentPath('')}>Drive</span>
            {currentPath.split('/').filter(Boolean).map((segment, idx, arr) => {
               const pathSoFar = arr.slice(0, idx + 1).join('/');
               return (
                 <div key={idx} className="flex items-center gap-2 shrink-0">
                   <ChevronRight className="w-3 h-3" />
                   <span 
                     className="text-[#E5E7EB] font-bold cursor-pointer hover:text-blue-400 transition-colors"
                     onClick={() => setCurrentPath(pathSoFar)}
                   >
                     {segment}
                   </span>
                 </div>
               );
            })}
          </div>
        </div>

        <div className="flex items-center gap-2 md:gap-4 shrink-0">
          <Button variant="ghost" size="icon" className="md:hidden text-white bg-[#2563EB] hover:bg-[#1d4ed8] rounded-full w-8 h-8 shrink-0" onClick={() => fileInputRef.current?.click()}>
            <Upload className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="icon" className="md:hidden text-white bg-[#2563EB] hover:bg-[#1d4ed8] rounded-full w-8 h-8 shrink-0" onClick={() => folderInputRef.current?.click()}>
            <Folder className="w-4 h-4 fill-white/20" />
          </Button>
          <div className="relative w-40 md:w-80 hidden sm:block">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[#4B5563]" />
            <Input 
              placeholder="Search files..." 
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="h-9 bg-[#111827] border-[#1F2937] text-sm pl-10 focus:ring-1 ring-[#2563EB]/50 rounded-xl transition-all w-full"
            />
          </div>
          <div className="flex items-center gap-1 md:gap-3">
             <Button 
                variant="ghost" 
                size="icon" 
                className="w-9 h-9 rounded-xl hover:bg-[#374151] hover:text-white transition-colors"
                onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
             >
                {theme === 'dark' ? <Sun className="w-4 h-4 text-[#9CA3AF] hover:text-white" /> : <Moon className="w-4 h-4 text-[#9CA3AF] hover:text-slate-800" />}
             </Button>
             
             <DropdownMenu>
               <DropdownMenuTrigger asChild>
                 <div className="hidden md:flex w-9 h-9 rounded-xl bg-[#1F2937] border border-[#374151] items-center justify-center cursor-pointer hover:bg-[#374151] transition-colors">
                   <User className="w-5 h-5 text-[#9CA3AF]" />
                 </div>
               </DropdownMenuTrigger>
               <DropdownMenuContent align="end" className="bg-[#111827] border-[#1F2937] text-[#E5E7EB] w-48">
                 <div className="px-3 py-2 text-xs text-[#9CA3AF] font-mono border-b border-[#1F2937] mb-1">
                    {authEmail || "Admin Session"}
                 </div>
                 <DropdownMenuItem className="gap-2 text-[#EF4444]" onClick={handleLogout}>
                    <Trash2 className="w-4 h-4" /> Disconnect Tunnel
                 </DropdownMenuItem>
               </DropdownMenuContent>
             </DropdownMenu>
          </div>
        </div>
      </div>

      {/* Mobile Sidebar Overlay */}
      <AnimatePresence>
        {isMobileMenuOpen && (
           <motion.div 
             initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
             className="fixed inset-0 z-50 bg-black/60 md:hidden flex"
             onClick={() => setIsMobileMenuOpen(false)}
           >
             <motion.div 
               initial={{ x: "-100%" }} animate={{ x: 0 }} exit={{ x: "-100%" }}
               transition={{ type: "spring", bounce: 0, duration: 0.3 }}
               className="w-64 bg-[#0B1220] h-full flex flex-col border-r border-[#1F2937]"
               onClick={e => e.stopPropagation()}
             >
                <SidebarContent />
             </motion.div>
           </motion.div>
        )}
      </AnimatePresence>

      {/* Main Content Areas */}
      <PanelGroup direction="horizontal" className="flex-1 w-full relative">
        {/* Sidebar - Pro Style */}
        <Panel defaultSize={18} minSize={14} className="hidden md:block bg-[#0B1220] border-r border-[#1F2937]">
          <SidebarContent />
        </Panel>

        <PanelResizeHandle className="hidden md:flex w-px bg-transparent hover:bg-[#2563EB]/30 transition-colors" />

        {/* File Browser Area */}
        <Panel defaultSize={62} minSize={40} className="bg-[#0B1220] flex flex-col relative w-full h-full"
          onDragOver={(e) => { e.preventDefault(); setIsDragging(true); e.dataTransfer.dropEffect = "copy"; }}
          onDragLeave={(e) => { e.preventDefault(); setIsDragging(false); }}
          onDrop={async (e) => {
            e.preventDefault();
            setIsDragging(false);
            if (e.dataTransfer.items) {
                const items = Array.from(e.dataTransfer.items);
                const allFiles: File[] = [];
                for (let i = 0; i < items.length; i++) {
                    const item = items[i].webkitGetAsEntry();
                    if (item) {
                        const files = await traverseFileTree(item);
                        allFiles.push(...files);
                    }
                }
                if (allFiles.length > 0) {
                    processFiles(allFiles);
                }
            } else if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                processFiles(Array.from(e.dataTransfer.files));
            }
          }}>
          
          <AnimatePresence>
            {isDragging && (
              <motion.div 
                initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                className="hidden md:flex absolute inset-0 z-50 bg-[#2563EB]/10 backdrop-blur-sm border-2 border-dashed border-[#2563EB] m-4 rounded-3xl flex-col items-center justify-center pointer-events-none"
              >
                <div className="w-20 h-20 bg-[#2563EB]/20 rounded-full flex items-center justify-center mb-6">
                  <Upload className="w-10 h-10 text-[#2563EB] animate-bounce" />
                </div>
                <h2 className="text-3xl font-bold text-white tracking-tight">Drop files here</h2>
                <p className="mt-2 text-[#9CA3AF]">Upload instantly to {currentPath ? `/${currentPath}` : 'Drive root'}</p>
              </motion.div>
            )}
          </AnimatePresence>

          <div className="flex flex-col h-full w-full">
            {/* Toolbar */}
            <div className="h-14 px-6 flex items-center justify-between border-b border-[#1F2937] bg-[#0B1220]/50 backdrop-blur-sm shrink-0">
                <div className="flex items-center gap-4">
                  {currentPath && (
                      <Button variant="ghost" size="icon" className="h-8 w-8 rounded-lg" onClick={navigateUp}>
                          <ChevronUp className="w-4 h-4"/>
                      </Button>
                  )}
                  <h2 className="font-bold text-lg flex items-center gap-2">
                      {activeTab} {isRefreshing && <div className="w-4 h-4 rounded-full border-2 border-t-transparent border-[#2563EB] animate-spin" />}
                  </h2>
                  <Badge variant="outline" className="text-[10px] font-mono py-0 text-[#9CA3AF] border-[#1F2937]">{files.length} items</Badge>
                </div>
                <div className="flex items-center gap-2">
                   <div className="bg-[#111827] p-1 rounded-xl border border-[#1F2937] flex">
                      <Button 
                        variant="ghost" size="icon" 
                        className={`h-7 w-7 rounded-sm ${viewMode === 'list' ? 'bg-[#1F2937] text-white' : 'text-[#4B5563]'}`}
                        onClick={() => setViewMode('list')}
                      >
                        <List className="w-3.5 h-3.5" />
                      </Button>
                      <Button 
                        variant="ghost" size="icon" 
                        className={`h-7 w-7 rounded-sm ${viewMode === 'grid' ? 'bg-[#1F2937] text-white' : 'text-[#4B5563]'}`}
                        onClick={() => setViewMode('grid')}
                      >
                        <LayoutGrid className="w-3.5 h-3.5" />
                      </Button>
                   </div>
                </div>
            </div>

            {/* List Header */}
            {viewMode === 'list' && (
              <div className="hidden md:grid grid-cols-12 px-8 py-3 bg-[#0B1220] border-b border-[#1F2937] text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] shrink-0">
                <div className="col-span-6 flex items-center gap-2 cursor-pointer hover:text-white transition-colors" onClick={() => toggleSort('name')}>
                  Name {sortField === 'name' && (sortDirection === 'asc' ? <ChevronUp className="w-3 h-3"/> : <ChevronDown className="w-3 h-3"/>)}
                </div>
                <div className="col-span-2 cursor-pointer hover:text-white transition-colors flex items-center gap-2" onClick={() => toggleSort('size')}>
                  Size {sortField === 'size' && (sortDirection === 'asc' ? <ChevronUp className="w-3 h-3"/> : <ChevronDown className="w-3 h-3"/>)}
                </div>
                <div className="col-span-4 text-right cursor-pointer hover:text-white transition-colors flex items-center justify-end gap-2" onClick={() => toggleSort('lastModified')}>
                  {sortField === 'lastModified' && (sortDirection === 'asc' ? <ChevronUp className="w-3 h-3"/> : <ChevronDown className="w-3 h-3"/>)} Modified
                </div>
              </div>
            )}

            <ScrollArea className="flex-1 w-full h-[0px]">
              {isRefreshing && files.length === 0 ? (
                <div className="flex flex-col gap-3 p-6">
                   {[1,2,3,4,5].map(i => (
                     <div key={i} className="w-full h-14 bg-[#1F2937]/50 rounded-lg animate-pulse" />
                   ))}
                </div>
              ) : filteredAndSortedFiles.length === 0 && !isRefreshing ? (
                 <div className="flex flex-col items-center justify-center p-20 text-[#4B5563]">
                    <div className="w-24 h-24 mb-6 opacity-20"><Cloud className="w-full h-full"/></div>
                    <h3 className="text-xl font-bold text-[#E5E7EB]">{searchQuery ? "No matches found" : "Nothing here yet"}</h3>
                    <p className="mt-2 text-sm text-center max-w-sm">{searchQuery ? "Try adjusting your search terms." : "Upload files or create folders to populate your storage space. Drag and drop works anywhere in this area."}</p>
                 </div>
              ) : (
                  <div className={viewMode === 'list' ? "divide-y divide-[#1F2937]/50" : "grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-4 p-4"}>
                    {filteredAndSortedFiles.map((file, index) => (
                      viewMode === 'list' ? (
                        <button
                          key={file.id}
                          onClick={() => file.isDirectory ? navigateTo(file.path) : setSelectedFile(file)}
                          className={`flex flex-col md:grid md:grid-cols-12 w-full px-4 md:px-6 py-3.5 md:py-3.5 text-left text-sm transition-all relative group pl-12 md:pl-12 min-h-[56px] md:min-h-0 ${
                            selectedFile?.id === file.id ? 'bg-[#2563EB]/5' : 'hover:bg-[#111827]/40'
                          } ${selectedFiles.has(file.id) ? 'bg-[#2563EB]/10' : ''}`}
                        >
                          <div className={`absolute left-3 md:left-4 top-1/2 -translate-y-1/2 flex items-center transition-opacity ${selectedFiles.has(file.id) ? 'opacity-100' : 'opacity-100 md:opacity-0 group-hover:opacity-100'}`} onClick={e => e.stopPropagation()}>
                            <div onClick={(e) => toggleSelection(e, file.id, index)} className={`w-6 h-6 md:w-4 md:h-4 rounded border flex items-center justify-center cursor-pointer transition-colors ${selectedFiles.has(file.id) ? 'bg-[#2563EB] border-[#2563EB]' : 'border-[#4B5563] hover:border-[#E5E7EB]'}`}>
                              {selectedFiles.has(file.id) && <Check className="w-4 h-4 md:w-3 md:h-3 text-white" />}
                            </div>
                          </div>
                          {selectedFile?.id === file.id && <div className="absolute left-0 top-1.5 bottom-1.5 w-1 bg-[#2563EB] rounded-r-full hidden md:block" />}
                          
                          <div className="w-full md:col-span-6 flex items-center gap-3 md:gap-4 pr-10 md:pr-4">
                            <div className="transition-transform group-hover:scale-110 duration-200 shrink-0">
                              {getFileIcon(file.name, file.isDirectory, "w-6 h-6 md:w-5 md:h-5")}
                            </div>
                            <span className="truncate flex-1 font-medium group-hover:text-[#2563EB] transition-colors">{file.name}</span>
                          </div>

                          {/* Mobile Subtitle */}
                          <div className="w-full pl-9 md:hidden flex items-center gap-2 mt-1 opacity-70">
                              <span className="text-[11px] text-[#9CA3AF] font-mono">{formatSize(file.size)}</span>
                              <span className="w-1 h-1 rounded-full bg-[#4B5563]" />
                              <span className="text-[11px] text-[#9CA3AF] font-mono">{formatDate(file.lastModified)}</span>
                          </div>

                          <div className="hidden md:flex col-span-2 font-mono text-xs text-[#4B5563] items-center">{formatSize(file.size)}</div>
                          <div className="hidden md:flex col-span-4 text-[#4B5563] items-center justify-end text-xs font-mono">{formatDate(file.lastModified)}</div>
                          
                          <div className="absolute right-1 md:right-4 top-1/2 -translate-y-1/2 opacity-100 md:opacity-0 md:group-hover:opacity-100 transition-opacity">
                            <DropdownMenu>
                              <DropdownMenuTrigger asChild>
                                <Button variant="ghost" size="icon" className="h-10 w-10 md:h-8 md:w-8 px-0 text-[#9CA3AF] hover:text-[#E5E7EB]" onClick={(e) => { e.stopPropagation(); setSelectedFile(file); }}>
                                  <MoreVertical className="w-5 h-5 md:w-4 md:h-4" />
                                </Button>
                              </DropdownMenuTrigger>
                              <DropdownMenuContent className="bg-[#111827] border-[#1F2937] text-[#E5E7EB]">
                                  <DropdownMenuItem className="gap-2" onClick={(e) => { e.stopPropagation(); handleDownload(file); }}><Download className="w-4 h-4" /> Download</DropdownMenuItem>
                                  <DropdownMenuItem className="gap-2" onClick={(e) => { e.stopPropagation(); handleRename(file); }}><FileEdit className="w-4 h-4" /> Rename</DropdownMenuItem>
                                  <DropdownMenuSeparator className="bg-[#1F2937]" />
                                  <DropdownMenuItem className="gap-2 text-[#EF4444]" onClick={(e) => { e.stopPropagation(); handleDelete(file); }}><Trash2 className="w-4 h-4" /> Delete</DropdownMenuItem>
                              </DropdownMenuContent>
                            </DropdownMenu>
                          </div>
                        </button>
                      ) : (
                        <Card 
                          key={file.id} 
                          onClick={() => file.isDirectory ? navigateTo(file.path) : setSelectedFile(file)}
                          className={`p-4 bg-[#111827]/40 border-[#1F2937] hover:border-[#2563EB]/50 transition-all cursor-pointer flex flex-col items-center justify-center group relative min-h-[140px] ${
                            selectedFile?.id === file.id ? 'ring-1 ring-[#2563EB] bg-[#2563EB]/5' : ''
                          } ${selectedFiles.has(file.id) ? 'ring-1 ring-[#2563EB] bg-[#2563EB]/10' : ''}`}
                        >
                          <div className={`absolute top-3 left-3 flex items-center transition-opacity z-10 ${selectedFiles.has(file.id) ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`} onClick={e => e.stopPropagation()}>
                            <div onClick={(e) => toggleSelection(e, file.id, index)} className={`w-4 h-4 rounded border flex items-center justify-center cursor-pointer transition-colors ${selectedFiles.has(file.id) ? 'bg-[#2563EB] border-[#2563EB]' : 'border-[#4B5563] hover:border-[#E5E7EB]'}`}>
                              {selectedFiles.has(file.id) && <Check className="w-3 h-3 text-white" />}
                            </div>
                          </div>
                          <div className="aspect-square w-[72px] h-[72px] shrink-0 rounded-xl bg-[#0B1220] flex items-center justify-center mb-4 transition-transform group-hover:scale-105">
                            {getFileIcon(file.name, file.isDirectory, "w-8 h-8")}
                          </div>
                          <span className="text-[11px] font-medium w-full text-center px-1 break-words line-clamp-2 leading-tight">{file.name}</span>
                          <p className="text-[9px] text-[#4B5563] mt-1 font-mono uppercase tracking-widest shrink-0">{formatSize(file.size)}</p>
                        </Card>
                      )
                    ))}
                  </div>
              )}
            </ScrollArea>
            
            {/* Floating Bulk Actions Bar */}
            <AnimatePresence>
              {selectedFiles.size > 0 && (
                <motion.div 
                  initial={{ opacity: 0, y: 50, scale: 0.95 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: 50, scale: 0.95 }}
                  className="absolute bottom-6 left-1/2 -translate-x-1/2 z-50 flex items-center gap-2 bg-[#1F2937]/90 backdrop-blur-xl border border-[#374151] shadow-2xl rounded-2xl p-2 px-4 shadow-black/50"
                >
                  <div className="flex items-center gap-2 px-2 border-r border-[#374151]">
                     <div className="w-6 h-6 rounded-full bg-[#2563EB] flex items-center justify-center text-xs font-bold">{selectedFiles.size}</div>
                     <span className="text-sm font-medium pr-2 text-white">{selectedFiles.size === 1 ? 'item' : 'items'} selected</span>
                  </div>
                  <Button variant="ghost" className="h-9 hover:bg-[#374151] hover:text-white text-[#E5E7EB]" onClick={handleBulkDownload}>
                    <Download className="w-4 h-4 mr-2" /> Download
                  </Button>
                  <Button variant="ghost" className="h-9 hover:bg-[#374151] hover:text-white text-[#E5E7EB]" onClick={handleBulkMove}>
                    <Move className="w-4 h-4 mr-2" /> Move
                  </Button>
                  <Button variant="ghost" className="h-9 hover:bg-red-500/20 hover:text-red-400 text-red-500 transition-colors" onClick={handleBulkDelete}>
                    <Trash2 className="w-4 h-4 mr-2" /> Delete
                  </Button>
                  <div className="w-px h-6 bg-[#374151] mx-1" />
                  <Button variant="ghost" size="icon" className="h-9 w-9 hover:bg-[#374151] text-[#9CA3AF]" onClick={clearSelection}>
                    <X className="w-4 h-4" />
                  </Button>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </Panel>

        <PanelResizeHandle className="w-px bg-transparent hover:bg-[#2563EB]/30 transition-colors" />

        {/* Info & Preview Panel */}
        <Panel defaultSize={20} minSize={20} className="hidden md:block bg-[#0B1220] border-l border-[#1F2937]">
          <PreviewContent />
        </Panel>
      </PanelGroup>

      {/* Mobile Preview Modal */}
      <AnimatePresence>
        {selectedFile && (
          <motion.div
            initial={{ opacity: 0, y: "100%" }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: "100%" }}
            transition={{ type: "spring", bounce: 0, duration: 0.4 }}
            className="fixed inset-0 z-50 bg-[#0B1220] md:hidden flex flex-col pointer-events-auto"
          >
            <PreviewContent />
          </motion.div>
        )}
      </AnimatePresence>

      {/* Upload Animation Overlay */}
      <AnimatePresence>
        {isUploading && (
          <motion.div 
            initial={{ opacity: 0, y: 100 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 100 }}
            className="fixed bottom-10 left-1/2 -translate-x-1/2 w-96 bg-[#111827] border border-[#2563EB]/40 p-4 rounded-2xl shadow-2xl z-50 flex items-center gap-4"
          >
             <div className="w-10 h-10 bg-[#2563EB]/10 rounded-xl flex items-center justify-center shrink-0">
                <Upload className="w-5 h-5 text-[#2563EB] animate-bounce" />
             </div>
             <div className="flex-1">
                <div className="flex justify-between mb-1.5 opacity-100">
                   <span className="text-xs font-bold uppercase tracking-wider text-white">Uploading...</span>
                   <span className="text-[10px] font-mono font-bold text-white">{uploadProgress}%</span>
                </div>
                <Progress value={uploadProgress} className="h-1 bg-[#0B1220]" />
                {folderProgress.total > 1 && (
                  <div className="mt-2 text-[10px] text-[#9CA3AF] font-medium flex justify-between gap-4">
                    <span>Folder Progress</span>
                    <span className="flex gap-2">
                        <span className="text-[#10B981]">{folderProgress.success} uploaded</span> &middot;
                        <span className="text-[#3B82F6]">{folderProgress.uploading} uploading</span> &middot;
                        <span className="text-[#EF4444]">{folderProgress.failed} failed</span>
                    </span>
                  </div>
                )}
             </div>
          </motion.div>
        )}
      </AnimatePresence>
      
      {failedUploads.length > 0 && (
          <motion.div 
            initial={{ opacity: 0, y: 100 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 100 }}
            className="fixed bottom-10 right-10 w-96 bg-[#111827] border border-[#EF4444]/40 p-4 rounded-2xl shadow-2xl z-50 flex flex-col gap-3"
          >
             <div className="flex justify-between items-center">
                <span className="text-xs font-bold text-[#EF4444]">{failedUploads.length} files failed</span>
                <Button size="sm" variant="outline" className="h-7 text-[10px] border-[#374151] hover:bg-[#374151]" onClick={() => setFailedUploads([])}><X className="w-3 h-3"/></Button>
             </div>
             <div className="max-h-32 overflow-y-auto text-xs text-[#9CA3AF] space-y-1">
                {failedUploads.map((f, i) => (
                    <div key={i} className="flex justify-between gap-4">
                        <span className="truncate flex-1">{f.file.name}</span>
                        <span className="text-[#EF4444] text-[10px] truncate w-32 text-right">{f.error}</span>
                    </div>
                ))}
             </div>
             <Button 
                size="sm" 
                className="h-8 bg-[#2563EB] hover:bg-[#1d4ed8] text-xs font-bold w-full" 
                onClick={() => {
                    const filesToRetry = failedUploads.map(f => f.file);
                    setFailedUploads([]);
                    processFiles(filesToRetry);
                }}
             >
                Retry Failed Files
             </Button>
          </motion.div>
      )}

      {Object.keys(sanitizedUploads).length > 0 && (
          <motion.div 
            initial={{ opacity: 0, y: 100 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 100 }}
            className="fixed bottom-10 left-10 w-96 bg-[#111827] border border-[#F59E0B]/40 p-4 rounded-2xl shadow-2xl z-50 flex flex-col gap-3"
          >
             <div className="flex justify-between items-center">
                <span className="text-xs font-bold text-[#F59E0B]">{Object.keys(sanitizedUploads).length} files renamed</span>
                <Button size="sm" variant="outline" className="h-7 text-[10px] border-[#374151] hover:bg-[#374151]" onClick={() => setSanitizedUploads({})}><X className="w-3 h-3"/></Button>
             </div>
             <div className="max-h-32 overflow-y-auto text-xs text-[#9CA3AF] space-y-1">
                {Object.entries(sanitizedUploads).map(([original, sanitized], i) => (
                    <div key={i} className="flex justify-between gap-4">
                        <span className="truncate flex-1" title={original}>{original}</span>
                        <span className="text-[#F59E0B] text-[10px] truncate w-32 text-right" title={sanitized}>&#8594; {sanitized}</span>
                    </div>
                ))}
             </div>
          </motion.div>
      )}

      {/* Share Configuration Modal */}
      <AnimatePresence>
        {showShareModal && (
          <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
            <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.95 }}>
              <Card className="w-full max-w-sm bg-[#111827] border-[#374151] p-6 shadow-2xl">
                <h3 className="text-xl font-bold text-white mb-2">Share Link</h3>
                <p className="text-xs text-[#9CA3AF] mb-6">Configure access controls for this link.</p>
                
                <div className="space-y-4 mb-6">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-white">Read-Only Access</span>
                    <button onClick={() => setShareConfig({...shareConfig, readOnly: !shareConfig.readOnly})} className={`w-10 h-6 rounded-full transition-colors ${shareConfig.readOnly ? 'bg-[#2563EB]' : 'bg-[#374151]'} relative`}>
                      <span className={`absolute top-1 w-4 h-4 bg-white rounded-full transition-all ${shareConfig.readOnly ? 'left-5' : 'left-1'}`} />
                    </button>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-white">Link Expiry</span>
                    <select value={shareConfig.expiry} onChange={e => setShareConfig({...shareConfig, expiry: e.target.value})} className="bg-[#0B1220] border border-[#374151] rounded-lg text-sm text-white px-3 py-1.5 outline-none">
                      <option value="1h">1 Hour</option>
                      <option value="24h">24 Hours</option>
                      <option value="7d">7 Days</option>
                      <option value="never">Never</option>
                    </select>
                  </div>
                </div>

                <div className="flex justify-end gap-3 mt-2">
                  <Button variant="ghost" className="text-gray-400 hover:text-white" onClick={() => setShowShareModal(false)}>Cancel</Button>
                  <Button className="bg-blue-600 hover:bg-blue-700 text-white" onClick={() => { setShowShareModal(false); toast.success("Share link configured & copied!"); }}>Copy Link</Button>
                </div>
              </Card>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}
