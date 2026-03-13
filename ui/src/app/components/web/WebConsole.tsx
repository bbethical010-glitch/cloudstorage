import { useState, useRef } from "react";
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
  Move
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
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "../ui/dropdown-menu";

const initialMockFiles = [
  { id: "1", name: "Photos", type: "Folder", size: "2.4 GB", items: 342, modified: "2024-03-10", icon: "folder" },
  { id: "2", name: "Documents", type: "Folder", size: "456 MB", items: 89, modified: "2024-03-09", icon: "folder" },
  { id: "3", name: "Videos", type: "Folder", size: "12.8 GB", items: 24, modified: "2024-03-08", icon: "folder" },
  { id: "4", name: "project-report.pdf", type: "PDF", size: "2.4 MB", modified: "2024-03-12", icon: "document" },
  { id: "5", name: "vacation-2024.zip", type: "Archive", size: "145 MB", modified: "2024-03-11", icon: "archive" },
  { id: "6", name: "presentation.mp4", type: "Video", size: "89 MB", modified: "2024-03-10", icon: "video" },
  { id: "7", name: "cover-image.png", type: "PNG", size: "4.2 MB", modified: "2024-03-09", icon: "image" },
  { id: "8", name: "budget.xlsx", type: "Excel", size: "1.2 MB", modified: "2024-03-08", icon: "document" },
];

export function WebConsole() {
  const [files, setFiles] = useState(initialMockFiles);
  const [selectedFileId, setSelectedFileId] = useState<string | null>("4");
  const [activeTab, setActiveTab] = useState("Drive");
  const [viewMode, setViewMode] = useState<"list" | "grid">("list");
  const [isUploading, setIsUploading] = useState(false);
  
  const selectedFile = files.find(f => f.id === selectedFileId);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const getFileIcon = (icon: string, className = "w-4 h-4") => {
    switch (icon) {
      case "folder": return <Folder className={`${className} text-[#2563EB] fill-[#2563EB]/10`} />;
      case "document": return <FileText className={`${className} text-[#9CA3AF]`} />;
      case "image": return <ImageIcon className={`${className} text-[#A855F7]`} />;
      case "video": return <Film className={`${className} text-[#F59E0B]`} />;
      case "archive": return <Archive className={`${className} text-[#6366F1]`} />;
      default: return <FileText className={`${className} text-[#9CA3AF]`} />;
    }
  };

  const handleUpload = () => {
    setIsUploading(true);
    setTimeout(() => {
      setIsUploading(false);
      toast.success("File uploaded successfully to your cloud drive");
    }, 2000);
  };

  const handleDelete = (id: string) => {
    setFiles(files.filter(f => f.id !== id));
    if (selectedFileId === id) setSelectedFileId(null);
    toast.error("File moved to trash");
  };

  const handleRename = (id: string) => {
    const newName = prompt("Enter new filename:");
    if (newName) {
      setFiles(files.map(f => f.id === id ? { ...f, name: newName } : f));
      toast.info("File renamed");
    }
  };

  return (
    <div className="h-screen bg-[#0B1220] text-[#E5E7EB] flex flex-col overflow-hidden font-sans selection:bg-[#2563EB]/30">
      {/* Top Bar - Refined */}
      <div className="h-14 border-b border-[#1F2937] flex items-center justify-between px-6 bg-[#0B1220] z-20">
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-3 group cursor-pointer">
            <div className="w-8 h-8 bg-gradient-to-br from-[#2563EB] to-[#A855F7] rounded-lg flex items-center justify-center shadow-lg shadow-blue-500/10">
              <Cloud className="w-5 h-5 text-white" />
            </div>
            <span className="font-bold tracking-tight text-lg">Easy Storage</span>
          </div>
          
          <Separator orientation="vertical" className="h-6 bg-[#1F2937]" />
          
          <div className="flex items-center gap-2 text-xs font-medium text-[#9CA3AF]">
            <Layers className="w-3.5 h-3.5" />
            <span>Drive</span>
            <ChevronRight className="w-3 h-3" />
            <span className="text-[#E5E7EB] font-bold">Personal</span>
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
             <Button variant="ghost" size="icon" className="h-9 w-9 rounded-xl text-[#9CA3AF] hover:text-[#E5E7EB]">
               <Settings className="w-4.5 h-4.5" />
             </Button>
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
            <div className="mb-8">
               <Button 
                onClick={() => fileInputRef.current?.click()}
                className="w-full bg-[#2563EB] hover:bg-[#1d4ed8] h-11 rounded-xl shadow-lg shadow-blue-500/10 gap-2 font-bold transition-all"
               >
                 <Plus className="w-5 h-5" /> New File
               </Button>
               <input type="file" ref={fileInputRef} className="hidden" onChange={handleUpload} />
            </div>

            <ScrollArea className="flex-1 -mx-2 px-2">
              <div className="space-y-6">
                <div className="space-y-1">
                  <h4 className="text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] px-3 mb-3">Main</h4>
                  {[
                    { label: "Drive", icon: HardDrive },
                    { label: "Shared Files", icon: Share2 },
                    { label: "Recent", icon: Clock },
                    { label: "Settings", icon: Settings },
                  ].map((item, i) => (
                    <button 
                      key={i}
                      onClick={() => setActiveTab(item.label)}
                      className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-all ${
                        activeTab === item.label ? 'bg-[#2563EB]/10 text-[#2563EB]' : 'text-[#9CA3AF] hover:bg-[#111827] hover:text-[#E5E7EB]'
                      }`}
                    >
                      <item.icon className={`w-4 h-4 ${activeTab === item.label ? "animate-pulse" : ""}`} />
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
                        <span className="text-[#E5E7EB]">47%</span>
                      </div>
                      <Progress value={47} className="h-1.5 bg-[#1F2937]" />
                    </div>
                    <div className="flex items-center gap-2 p-2.5 bg-[#111827] rounded-xl border border-[#374151]/30">
                      <div className="w-2 h-2 rounded-full bg-[#22C55E]" />
                      <span className="text-[10px] font-bold text-[#9CA3AF] uppercase truncate">USB Drive • Connected</span>
                    </div>
                  </div>
                </div>
              </div>
            </ScrollArea>
          </div>
        </Panel>

        <PanelResizeHandle className="w-px bg-transparent hover:bg-[#2563EB]/30 transition-colors" />

        {/* File Browser Area */}
        <Panel defaultSize={62} minSize={40} className="bg-[#0B1220]">
          <div className="flex flex-col h-full">
            {/* Toolbar */}
            <div className="h-14 px-6 flex items-center justify-between border-b border-[#1F2937] bg-[#0B1220]/50 backdrop-blur-sm">
                <div className="flex items-center gap-4">
                  <h2 className="font-bold text-lg">{activeTab}</h2>
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
                   <Button variant="outline" size="sm" className="h-9 border-[#1F2937] bg-[#111827] rounded-xl gap-2 text-xs font-bold">
                     <Upload className="w-3.5 h-3.5" /> Upload
                   </Button>
                </div>
            </div>

            {/* List Header */}
            {viewMode === 'list' && (
              <div className="grid grid-cols-12 px-8 py-3 bg-[#0B1220] border-b border-[#1F2937] text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em]">
                <div className="col-span-6 flex items-center gap-2">Name <ChevronDown className="w-3 h-3" /></div>
                <div className="col-span-2">Size</div>
                <div className="col-span-2">Kind</div>
                <div className="col-span-2 text-right">Modified</div>
              </div>
            )}

            <ScrollArea className="flex-1 p-2">
              <div className={viewMode === 'list' ? "divide-y divide-[#1F2937]/50" : "grid grid-cols-4 lg:grid-cols-6 gap-4 p-4"}>
                {files.map((file, i) => (
                  viewMode === 'list' ? (
                    <button
                      key={file.id}
                      onClick={() => setSelectedFileId(file.id)}
                      onContextMenu={(e) => { e.preventDefault(); handleRename(file.id); }}
                      className={`grid grid-cols-12 w-full px-6 py-3.5 text-left text-sm transition-all relative group ${
                        selectedFileId === file.id ? 'bg-[#2563EB]/5' : 'hover:bg-[#111827]/40'
                      }`}
                    >
                      {selectedFileId === file.id && <div className="absolute left-0 top-1.5 bottom-1.5 w-1 bg-[#2563EB] rounded-r-full" />}
                      <div className="col-span-6 flex items-center gap-4 pr-4">
                        <div className="transition-transform group-hover:scale-110 duration-200">
                          {getFileIcon(file.icon, "w-5 h-5")}
                        </div>
                        <span className="truncate font-medium group-hover:text-[#2563EB] transition-colors">{file.name}</span>
                      </div>
                      <div className="col-span-2 font-mono text-xs text-[#4B5563] flex items-center">{file.size}</div>
                      <div className="col-span-2 text-[#4B5563] flex items-center text-xs">{file.type}</div>
                      <div className="col-span-2 text-[#4B5563] flex items-center justify-end text-xs font-mono">{file.modified}</div>
                      
                      <div className="absolute right-4 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100 transition-opacity">
                         <DropdownMenu>
                           <DropdownMenuTrigger asChild>
                             <Button variant="ghost" size="icon" className="h-8 w-8 text-[#4B5563] hover:text-[#E5E7EB]">
                               <MoreVertical className="w-4 h-4" />
                             </Button>
                           </DropdownMenuTrigger>
                           <DropdownMenuContent className="bg-[#111827] border-[#1F2937] text-[#E5E7EB]">
                              <DropdownMenuItem className="gap-2" onClick={() => toast.success("Share link created")}><Share2 className="w-4 h-4" /> Share</DropdownMenuItem>
                              <DropdownMenuItem className="gap-2" onClick={() => handleRename(file.id)}><FileEdit className="w-4 h-4" /> Rename</DropdownMenuItem>
                              <DropdownMenuItem className="gap-2"><Move className="w-4 h-4" /> Move To</DropdownMenuItem>
                              <DropdownMenuSeparator className="bg-[#1F2937]" />
                              <DropdownMenuItem className="gap-2 text-[#EF4444]" onClick={() => handleDelete(file.id)}><Trash2 className="w-4 h-4" /> Delete</DropdownMenuItem>
                           </DropdownMenuContent>
                         </DropdownMenu>
                      </div>
                    </button>
                  ) : (
                    <Card 
                      key={file.id} 
                      onClick={() => setSelectedFileId(file.id)}
                      className={`p-4 bg-[#111827]/40 border-[#1F2937] hover:border-[#2563EB]/50 transition-all cursor-pointer flex flex-col items-center group relative ${
                        selectedFileId === file.id ? 'ring-1 ring-[#2563EB] bg-[#2563EB]/5' : ''
                      }`}
                    >
                      <div className="aspect-square w-full rounded-xl bg-[#0B1220] flex items-center justify-center mb-4 transition-transform group-hover:scale-105">
                         {getFileIcon(file.icon, "w-10 h-10")}
                      </div>
                      <span className="text-[11px] font-medium truncate w-full text-center px-1">{file.name}</span>
                      <p className="text-[9px] text-[#4B5563] mt-1 font-mono uppercase tracking-widest">{file.size}</p>
                    </Card>
                  )
                ))}
              </div>
            </ScrollArea>
          </div>
        </Panel>

        <PanelResizeHandle className="w-px bg-transparent hover:bg-[#2563EB]/30 transition-colors" />

        {/* Info & Preview Panel */}
        <Panel defaultSize={20} minSize={20} className="bg-[#0B1220] border-l border-[#1F2937]">
          <AnimatePresence mode="wait">
            {selectedFile ? (
              <motion.div 
                key={selectedFileId}
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 20 }}
                className="h-full flex flex-col p-6"
              >
                <div className="flex items-center justify-between mb-8">
                   <h3 className="font-bold text-sm tracking-widest uppercase text-[#4B5563]">Details</h3>
                   <Button variant="ghost" size="icon" className="h-8 w-8 text-[#4B5563]" onClick={() => setSelectedFileId(null)}>
                     <ChevronUp className="w-4 h-4" />
                   </Button>
                </div>

                <div className="aspect-square w-full bg-gradient-to-br from-[#111827] to-[#0B1220] rounded-[2rem] border border-[#1F2937] flex items-center justify-center mb-8 shadow-2xl relative overflow-hidden group">
                  <div className="absolute inset-0 bg-[#2563EB]/5 opacity-0 group-hover:opacity-100 transition-opacity" />
                  {getFileIcon(selectedFile.icon, "w-20 h-20 transition-transform group-hover:scale-110 duration-500")}
                  <div className="absolute bottom-4 left-4 right-4">
                     <div className="bg-[#0B1220]/80 backdrop-blur-md p-3 rounded-2xl border border-[#374151]/30 opacity-0 group-hover:opacity-100 translate-y-4 group-hover:translate-y-0 transition-all">
                        <p className="text-[10px] text-center font-bold text-[#2563EB] uppercase tracking-widest">Visual Preview Pending</p>
                     </div>
                  </div>
                </div>
                
                <div className="flex-1 space-y-8 overflow-hidden">
                  <div>
                    <h3 className="text-xl font-bold truncate pr-4 text-white leading-tight">{selectedFile.name}</h3>
                    <div className="flex items-center gap-2 mt-2">
                       <Badge className="bg-[#2563EB]/10 text-[#2563EB] border-transparent text-[10px] uppercase font-bold tracking-widest">{selectedFile.type}</Badge>
                       <span className="text-[10px] text-[#4B5563] font-mono uppercase tracking-widest">{selectedFile.size}</span>
                    </div>
                  </div>

                  <div className="space-y-5 pt-6 border-t border-[#1F2937]/50">
                    <div className="grid grid-cols-2 gap-4">
                       <div className="space-y-1">
                          <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">Modified</label>
                          <p className="text-xs font-mono text-[#E5E7EB]">{selectedFile.modified}</p>
                       </div>
                       <div className="space-y-1 text-right">
                          <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">Permissions</label>
                          <p className="text-xs text-[#22C55E] font-bold">Encrypted</p>
                       </div>
                    </div>
                    <div className="space-y-2">
                      <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">Location</label>
                      <div className="flex items-center gap-2 px-3 py-2 bg-[#111827] border border-[#1F2937] rounded-xl">
                        <HardDrive className="w-3.5 h-3.5 text-[#2563EB]" />
                        <p className="text-[10px] font-mono text-[#9CA3AF] truncate">/Storage/Personal/Drive</p>
                      </div>
                    </div>
                  </div>

                  <div className="pt-8 space-y-3">
                    <Button 
                      className="w-full bg-[#2563EB] hover:bg-[#1d4ed8] h-11 rounded-xl gap-2 font-bold shadow-lg shadow-blue-500/10 active:scale-95 transition-all"
                      onClick={() => toast.success("Preparing download...")}
                    >
                      <Download className="w-4 h-4" /> Download
                    </Button>
                    <div className="grid grid-cols-2 gap-3">
                       <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs" onClick={() => toast.success("Private share linked enabled")}>
                         <Share2 className="w-3.5 h-3.5 text-[#A855F7]" /> Share
                       </Button>
                       <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs text-[#EF4444]" onClick={() => handleDelete(selectedFile.id)}>
                         <Trash2 className="w-3.5 h-3.5" /> Delete
                       </Button>
                    </div>
                  </div>
                </div>

                <div className="pt-6 border-t border-[#1F2937]">
                  <button className="flex items-center gap-2 text-[9px] text-[#4B5563] font-bold uppercase tracking-[0.2em] hover:text-[#E5E7EB] transition-colors">
                    <Info className="w-3.5 h-3.5" /> Full Properties
                  </button>
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
             <div className="w-10 h-10 bg-[#2563EB]/10 rounded-xl flex items-center justify-center">
                <Upload className="w-5 h-5 text-[#2563EB] animate-bounce" />
             </div>
             <div className="flex-1">
                <div className="flex justify-between mb-1.5">
                   <span className="text-xs font-bold uppercase tracking-wider">Uploading...</span>
                   <span className="text-[10px] font-mono">42%</span>
                </div>
                <Progress value={42} className="h-1 bg-[#0B1220]" />
             </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
