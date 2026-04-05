import { useState, useEffect, useCallback } from "react";
import { motion, AnimatePresence } from "motion/react";
import { 
  ArrowLeft, 
  ChevronUp, 
  Download, 
  FileEdit, 
  Share2, 
  Move, 
  Trash2, 
  HardDrive,
  Eye,
  AlertCircle
} from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { toast } from "sonner";

interface FileNode {
  id: string;
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  lastModified: number;
}

interface PreviewModalProps {
  selectedFile: FileNode | null;
  currentPath: string;
  apiFetch: (endpoint: string, options?: RequestInit) => Promise<any>;
  onClose: () => void;
  onRename: (file: FileNode) => void;
  onShare: (file: FileNode) => void;
  onMove: (file: FileNode) => void;
  onDelete: (file: FileNode) => void;
  formatSize: (bytes: number | undefined) => string;
  formatDate: (ms: number | undefined) => string;
  getFileIcon: (fileName: string, isDirectory: boolean, className?: string) => React.ReactNode;
}

const SquareLoader = ({ size = "md", color = "var(--accent)" }: { size?: "sm" | "md" | "lg", color?: string }) => {
  const scale = size === "sm" ? 0.3 : size === "lg" ? 1.2 : 0.8;
  return (
    <div className="premium-loader" style={{ transform: `rotate(45deg) scale(${scale})`, opacity: 0.8 }}>
      {[0, 1, 2, 3, 4, 5, 6, 7].map((i) => (
        <div key={i} className="loader-square" style={{ backgroundColor: color }} />
      ))}
    </div>
  );
};

export function PreviewModal({
  selectedFile,
  currentPath,
  apiFetch,
  onClose,
  onRename,
  onShare,
  onMove,
  onDelete,
  formatSize,
  formatDate,
  getFileIcon
}: PreviewModalProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedFile || selectedFile.isDirectory) {
      setObjectUrl(null);
      setError(null);
      return;
    }

    const ext = selectedFile.name.split('.').pop()?.toLowerCase() || '';
    const isPreviewable = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'mp4', 'webm', 'mov', 'avi', 'pdf'].includes(ext);

    if (!isPreviewable) {
      setObjectUrl(null);
      setError(null);
      return;
    }

    let isMounted = true;
    const controller = new AbortController();

    const fetchMedia = async () => {
      setIsLoading(true);
      setError(null);
      
      try {
        const endpoint = `/api/download?path=${encodeURIComponent(selectedFile.path || currentPath)}&file=${encodeURIComponent(selectedFile.name)}`;
        
        const res = await apiFetch(endpoint, { 
          signal: controller.signal 
        });

        if (!res.ok) {
          throw new Error(res.status === 401 ? "Unauthorized: Incorrect node password." : "Failed to load media.");
        }

        const blob = await res.blob();
        if (isMounted) {
          const url = URL.createObjectURL(blob);
          setObjectUrl(url);
        }
      } catch (err: any) {
        if (err.name === 'AbortError') return;
        if (isMounted) {
          setError(err.message || "Failed to load media: Node unauthorized or offline.");
        }
      } finally {
        if (isMounted) {
          setIsLoading(false);
        }
      }
    };

    void fetchMedia();

    return () => {
      isMounted = false;
      controller.abort();
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [selectedFile, currentPath, apiFetch]);

  const handleDownload = useCallback(() => {
    if (!selectedFile) return;

    if (objectUrl) {
      // Efficiently reuse the existing Blob for native download
      const a = document.createElement('a');
      a.href = objectUrl;
      a.download = selectedFile.name;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      toast.success("Download started (from cache)");
    } else {
      // Fallback for non-previewable files or if Blob failed (will use native fetch check)
      // This is handled by the parent WebConsole.handleDownload
      toast.info("Preparing native download...");
    }
  }, [selectedFile, objectUrl]);

  return (
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
             <Button variant="ghost" size="icon" className="h-8 w-8 text-[#E5E7EB] md:hidden" onClick={onClose}>
               <ArrowLeft className="w-5 h-5 text-white" />
             </Button>
             <h3 className="font-bold text-sm tracking-widest uppercase text-[#4B5563] hidden md:block">Details</h3>
             <div className="flex-1 md:hidden" />
             <Button variant="ghost" size="icon" className="h-8 w-8 text-[#4B5563]" onClick={onClose}>
               <ChevronUp className="w-4 h-4 hidden md:block" />
             </Button>
          </div>

          <div className="aspect-square shrink-0 w-full min-h-[180px] bg-gradient-to-br from-[#111827] to-[#0B1220] rounded-[2rem] border border-[#1F2937] flex items-center justify-center mb-8 shadow-2xl relative overflow-hidden group">
            <div className="absolute inset-0 bg-[#2563EB]/5 opacity-0 group-hover:opacity-100 transition-opacity" />
            
            {isLoading ? (
              <div className="flex flex-col items-center gap-4">
                <SquareLoader size="lg" color="#2563EB" />
                <p className="text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] animate-pulse">Buffering...</p>
              </div>
            ) : error ? (
              <div className="flex flex-col items-center gap-4 p-8 text-center">
                <AlertCircle className="w-12 h-12 text-[#EF4444] opacity-50" />
                <p className="text-xs text-[#EF4444] font-medium leading-relaxed">{error}</p>
              </div>
            ) : objectUrl ? (
              <div className="w-full h-full relative flex items-center justify-center">
                {(() => {
                  const ext = selectedFile.name.split('.').pop()?.toLowerCase() || '';
                  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) {
                    return (
                      <img 
                        src={objectUrl} 
                        className="object-contain w-full h-full absolute inset-0 text-transparent" 
                        alt="Preview"
                      />
                    );
                  }
                  if (['mp4', 'webm', 'mov', 'avi'].includes(ext)) {
                    return (
                      <video 
                        src={objectUrl} 
                        controls 
                        className="object-contain w-full h-full absolute inset-0 bg-black/20" 
                      />
                    );
                  }
                  if (ext === 'pdf') {
                    return <iframe src={objectUrl} className="w-full h-full bg-white rounded-2xl" title="PDF Preview" />;
                  }
                  return null;
                })()}
              </div>
            ) : (
              getFileIcon(selectedFile.name, selectedFile.isDirectory, "w-20 h-20 transition-transform group-hover:scale-110 duration-500 z-10 relative")
            )}
            
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
                onClick={handleDownload}
              >
                <Download className="w-4 h-4" /> Download
              </Button>
              <div className="grid grid-cols-2 gap-3">
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs" onClick={() => onRename(selectedFile)}>
                   <FileEdit className="w-3.5 h-3.5" /> Rename
                 </Button>
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs" onClick={() => onShare(selectedFile)}>
                   <Share2 className="w-3.5 h-3.5" /> Share
                 </Button>
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs" onClick={() => onMove(selectedFile)}>
                   <Move className="w-3.5 h-3.5" /> Move
                 </Button>
                 <Button variant="outline" className="border-[#1F2937] bg-[#111827] h-10 rounded-xl gap-2 hover:bg-[#1F2937] font-bold text-xs text-[#EF4444]" onClick={() => onDelete(selectedFile)}>
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
}
