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
  Home
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
  const [isRefreshing, setIsRefreshing] = useState(false);
  
  const fileInputRef = useRef<HTMLInputElement>(null);

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
    const params = new URLSearchParams(window.location.hash.split('?')[1]);
    const pwd = params.get('pwd') || '';
    return { 'Authorization': `Bearer ${pwd}` };
  };

  useEffect(() => {
    loadFiles(currentPath);
  }, [currentPath, activeTab]);

  const loadFiles = async (path: string) => {
    setIsRefreshing(true);
    setSelectedFile(null);
    try {
      const endpoint = activeTab === "Trash" ? "/api/trash" : `/api/files?path=${encodeURIComponent(path)}`;
      const res = await fetch(`${getBaseUrl()}${endpoint}`, { headers: getHeaders() });
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

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const fileList = e.target.files;
    if (!fileList || fileList.length === 0) return;
    
    setIsUploading(true);
    setUploadProgress(0);

    const formData = new FormData();
    for (let i = 0; i < fileList.length; i++) {
        formData.append("file", fileList[i]);
    }

    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${getBaseUrl()}/api/upload?path=${encodeURIComponent(currentPath)}`);
    
    const headers = getHeaders();
    if (headers.Authorization) {
        xhr.setRequestHeader('Authorization', headers.Authorization);
    }

    xhr.upload.addEventListener("progress", (event) => {
      if (event.lengthComputable) {
        const percent = Math.round((event.loaded / event.total) * 100);
        setUploadProgress(percent);
      }
    });

    xhr.onload = () => {
      setIsUploading(false);
      if (xhr.status === 200) {
        toast.success("Upload complete!");
        loadFiles(currentPath);
      } else {
        toast.error("Upload failed: " + xhr.responseText);
      }
      if (fileInputRef.current) fileInputRef.current.value = "";
    };

    xhr.onerror = () => {
      setIsUploading(false);
      toast.error("Upload network error");
    };

    xhr.send(formData);
  };

  const handleDownload = (file: FileNode) => {
    const url = file.isDirectory 
        ? `${getBaseUrl()}/api/download_folder?path=${encodeURIComponent(currentPath)}&folder=${encodeURIComponent(file.name)}`
        : `${getBaseUrl()}/api/download?path=${encodeURIComponent(currentPath)}&file=${encodeURIComponent(file.name)}`;
    
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

  const navigateTo = (folderName: string) => {
    const newPath = currentPath ? `${currentPath}/${folderName}` : folderName;
    setCurrentPath(newPath);
  };

  const navigateUp = () => {
    if (!currentPath) return;
    const segments = currentPath.split('/');
    segments.pop();
    setCurrentPath(segments.join('/'));
  };

  return (
    <div className="h-screen bg-[#0B1220] text-[#E5E7EB] flex flex-col overflow-hidden font-sans selection:bg-[#2563EB]/30">
      {/* Top Bar - Refined */}
      <div className="h-14 border-b border-[#1F2937] flex items-center justify-between px-6 bg-[#0B1220] z-20">
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-3 group cursor-pointer" onClick={() => { setActiveTab("Drive"); setCurrentPath(""); }}>
            <div className="w-8 h-8 bg-gradient-to-br from-[#2563EB] to-[#A855F7] rounded-lg flex items-center justify-center shadow-lg shadow-blue-500/10">
              <Cloud className="w-5 h-5 text-white" />
            </div>
            <span className="font-bold tracking-tight text-lg">Easy Storage</span>
          </div>
          
          <Separator orientation="vertical" className="h-6 bg-[#1F2937]" />
          
          <div className="flex items-center gap-2 text-xs font-medium text-[#9CA3AF]">
            <Layers className="w-3.5 h-3.5" />
            <span>Drive</span>
            {currentPath.split('/').filter(Boolean).map((segment, idx) => (
               <div key={idx} className="flex items-center gap-2">
                 <ChevronRight className="w-3 h-3" />
                 <span className="text-[#E5E7EB] font-bold">{segment}</span>
               </div>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="relative w-80">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[#4B5563]" />
            <Input 
              placeholder="Search files and folders..." 
              className="h-9 bg-[#111827] border-[#1F2937] text-sm pl-10 focus:ring-1 ring-[#2563EB]/50 rounded-xl transition-all w-full"
            />
          </div>
          <div className="flex items-center gap-1">
             <div className="w-9 h-9 rounded-xl bg-[#1F2937] border border-[#374151] flex items-center justify-center cursor-pointer hover:bg-[#374151] transition-colors">
               <User className="w-5 h-5 text-[#9CA3AF]" />
             </div>
          </div>
        </div>
      </div>

      {/* Main Content Areas */}
      <PanelGroup direction="horizontal" className="flex-1">
        {/* Sidebar - Pro Style */}
        <Panel defaultSize={18} minSize={14} className="bg-[#0B1220] border-r border-[#1F2937]">
          <div className="flex flex-col h-full p-4">
            <div className="mb-8 space-y-2">
               <Button 
                onClick={() => fileInputRef.current?.click()}
                className="w-full bg-[#2563EB] hover:bg-[#1d4ed8] h-11 rounded-xl shadow-lg shadow-blue-500/10 gap-2 font-bold transition-all"
               >
                 <Upload className="w-5 h-5" /> Upload File
               </Button>
               <Button 
                variant="outline"
                onClick={handleCreateFolder}
                className="w-full bg-transparent border-[#1F2937] hover:bg-[#111827] h-10 rounded-xl gap-2 font-bold transition-all"
               >
                 <Plus className="w-4 h-4" /> New Folder
               </Button>
               <input type="file" ref={fileInputRef} className="hidden" multiple onChange={handleUpload} />
            </div>

            <ScrollArea className="flex-1 -mx-2 px-2">
              <div className="space-y-6">
                <div className="space-y-1">
                  <h4 className="text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] px-3 mb-3">Main</h4>
                  {[
                    { label: "Drive", icon: HardDrive },
                    { label: "Trash", icon: Trash2 },
                  ].map((item, i) => (
                    <button 
                      key={i}
                      onClick={() => { setActiveTab(item.label); setCurrentPath(""); }}
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
                        <span>Storage Used</span>
                        <span className="text-[#E5E7EB]">Live</span>
                      </div>
                      <Progress value={Math.random() * 100} className="h-1.5 bg-[#1F2937]" />
                    </div>
                  </div>
                </div>
              </div>
            </ScrollArea>
          </div>
        </Panel>

        <PanelResizeHandle className="w-px bg-transparent hover:bg-[#2563EB]/30 transition-colors" />

        {/* File Browser Area */}
        <Panel defaultSize={62} minSize={40} className="bg-[#0B1220] flex flex-col relative w-full h-full"
          onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = "copy"; }}
          onDrop={(e) => {
            e.preventDefault();
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
              // Create a synthetic event matching signature
              handleUpload({ target: { files: e.dataTransfer.files } } as any);
            }
          }}>
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
              <div className="grid grid-cols-12 px-8 py-3 bg-[#0B1220] border-b border-[#1F2937] text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] shrink-0">
                <div className="col-span-6 flex items-center gap-2">Name <ChevronDown className="w-3 h-3" /></div>
                <div className="col-span-2">Size</div>
                <div className="col-span-4 text-right">Modified</div>
              </div>
            )}

            <ScrollArea className="flex-1 w-full h-[0px]">
              {files.length === 0 && !isRefreshing ? (
                 <div className="flex flex-col items-center justify-center p-20 text-[#4B5563]">
                    <div className="w-24 h-24 mb-6 opacity-20"><Cloud className="w-full h-full"/></div>
                    <h3 className="text-xl font-bold text-[#E5E7EB]">Nothing here yet</h3>
                    <p className="mt-2 text-sm text-center max-w-sm">Upload files or create folders to populate your storage space. Drag and drop works anywhere in this area.</p>
                 </div>
              ) : (
                  <div className={viewMode === 'list' ? "divide-y divide-[#1F2937]/50" : "grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-4 p-4"}>
                    {files.map((file) => (
                      viewMode === 'list' ? (
                        <button
                          key={file.id}
                          onClick={() => file.isDirectory ? navigateTo(file.name) : setSelectedFile(file)}
                          className={`grid grid-cols-12 w-full px-6 py-3.5 text-left text-sm transition-all relative group ${
                            selectedFile?.id === file.id ? 'bg-[#2563EB]/5' : 'hover:bg-[#111827]/40'
                          }`}
                        >
                          {selectedFile?.id === file.id && <div className="absolute left-0 top-1.5 bottom-1.5 w-1 bg-[#2563EB] rounded-r-full" />}
                          <div className="col-span-6 flex items-center gap-4 pr-4">
                            <div className="transition-transform group-hover:scale-110 duration-200">
                              {getFileIcon(file.name, file.isDirectory, "w-5 h-5")}
                            </div>
                            <span className="truncate font-medium group-hover:text-[#2563EB] transition-colors">{file.name}</span>
                          </div>
                          <div className="col-span-2 font-mono text-xs text-[#4B5563] flex items-center">{formatSize(file.size)}</div>
                          <div className="col-span-4 text-[#4B5563] flex items-center justify-end text-xs font-mono">{formatDate(file.lastModified)}</div>
                          
                          <div className="absolute right-4 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100 transition-opacity">
                            <DropdownMenu>
                              <DropdownMenuTrigger asChild>
                                <Button variant="ghost" size="icon" className="h-8 w-8 text-[#4B5563] hover:text-[#E5E7EB]" onClick={(e) => { e.stopPropagation(); setSelectedFile(file); }}>
                                  <MoreVertical className="w-4 h-4" />
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
                          onClick={() => file.isDirectory ? navigateTo(file.name) : setSelectedFile(file)}
                          className={`p-4 bg-[#111827]/40 border-[#1F2937] hover:border-[#2563EB]/50 transition-all cursor-pointer flex flex-col items-center justify-center group relative min-h-[140px] ${
                            selectedFile?.id === file.id ? 'ring-1 ring-[#2563EB] bg-[#2563EB]/5' : ''
                          }`}
                        >
                          <div className="aspect-square w-[72px] h-[72px] shrink-0 rounded-xl bg-[#0B1220] flex items-center justify-center mb-4 transition-transform group-hover:scale-105">
                            {getFileIcon(file.name, file.isDirectory, "w-8 h-8")}
                          </div>
                          <span className="text-[11px] font-medium w-full text-center px-1 break-words line-clamp-2">{file.name}</span>
                          <p className="text-[9px] text-[#4B5563] mt-1 font-mono uppercase tracking-widest shrink-0">{formatSize(file.size)}</p>
                        </Card>
                      )
                    ))}
                  </div>
              )}
            </ScrollArea>
          </div>
        </Panel>

        <PanelResizeHandle className="w-px bg-transparent hover:bg-[#2563EB]/30 transition-colors" />

        {/* Info & Preview Panel */}
        <Panel defaultSize={20} minSize={20} className="bg-[#0B1220] border-l border-[#1F2937]">
          <AnimatePresence mode="wait">
            {selectedFile ? (
              <motion.div 
                key={selectedFile.id}
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 20 }}
                className="h-full flex flex-col p-6"
              >
                <div className="flex items-center justify-between mb-8 shrink-0">
                   <h3 className="font-bold text-sm tracking-widest uppercase text-[#4B5563]">Details</h3>
                   <Button variant="ghost" size="icon" className="h-8 w-8 text-[#4B5563]" onClick={() => setSelectedFile(null)}>
                     <ChevronUp className="w-4 h-4" />
                   </Button>
                </div>

                <div className="aspect-square shrink-0 w-full min-h-[180px] bg-gradient-to-br from-[#111827] to-[#0B1220] rounded-[2rem] border border-[#1F2937] flex items-center justify-center mb-8 shadow-2xl relative overflow-hidden group">
                  <div className="absolute inset-0 bg-[#2563EB]/5 opacity-0 group-hover:opacity-100 transition-opacity" />
                  
                  {['png', 'jpg', 'jpeg', 'gif'].includes(selectedFile.name.split('.').pop()?.toLowerCase() || '') ? (
                      <img src={`${getBaseUrl()}/api/download?path=${encodeURIComponent(currentPath)}&file=${encodeURIComponent(selectedFile.name)}`} 
                           className="object-cover w-full h-full" alt="Preview" />
                  ) : (
                      getFileIcon(selectedFile.name, selectedFile.isDirectory, "w-20 h-20 transition-transform group-hover:scale-110 duration-500")
                  )}
                  
                  <div className="absolute bottom-4 left-4 right-4">
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
                       <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs text-[#EF4444]" onClick={() => handleDelete(selectedFile)}>
                         <Trash2 className="w-3.5 h-3.5" /> Move
                       </Button>
                    </div>
                  </div>
                </div>
              </motion.div>
            ) : (
              <div className="h-full flex flex-col items-center justify-center text-[#4B5563] p-12 text-center">
                <div className="w-20 h-20 bg-[#111827] rounded-[2.5rem] border border-[#1F2937] flex items-center justify-center mb-6">
                  <Eye className="w-8 h-8 opacity-20" />
                </div>
                <h3 className="text-sm font-bold uppercase tracking-widest text-[#E5E7EB]">No Preview</h3>
                <p className="text-xs mt-3 leading-relaxed">Select a document or image to see high-resolution details</p>
              </div>
            )}
          </AnimatePresence>
        </Panel>
      </PanelGroup>

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
             </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
