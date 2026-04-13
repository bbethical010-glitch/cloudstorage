import { useState, useEffect, useCallback, useMemo } from "react";
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
  AlertCircle,
  FileQuestion,
  ExternalLink
} from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { toast } from "sonner";
import {
  fetchFileBlob,
  SUPPORTED_PREVIEW_TYPES,
  MIME_MAP,
  type FileNode
} from "./PreviewManager";

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
  /** Optional: pass the current connection mode to optimize video streaming */
  connectionMode?: "p2p" | "relay" | "local";
  /** Optional: for building authenticated direct download URLs in relay mode */
  getAuthenticatedUrl?: (path: string) => string;
}

// ─── MIME Detection Helpers ────────────────────────────────────────────────────

const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico']);
const VIDEO_EXTENSIONS = new Set(['mp4', 'webm', 'mov', 'avi', 'mkv']);
const AUDIO_EXTENSIONS = new Set(['mp3', 'wav', 'ogg', 'flac', 'aac']);
const TEXT_EXTENSIONS = new Set(['txt', 'md', 'json', 'log', 'csv', 'yaml', 'yml', 'toml']);
const CODE_EXTENSIONS = new Set(['js', 'ts', 'tsx', 'jsx', 'py', 'java', 'kt', 'go', 'rs', 'c', 'cpp', 'h', 'css', 'scss', 'html', 'htm', 'xml']);
const PDF_EXTENSIONS = new Set(['pdf']);

type PreviewCategory = 'image' | 'video' | 'audio' | 'text' | 'code' | 'pdf' | 'unsupported';

function getPreviewCategory(ext: string, mimeType = ''): PreviewCategory {
  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType.startsWith('video/')) return 'video';
  if (mimeType.startsWith('audio/')) return 'audio';
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType.startsWith('text/')) return 'text';

  if (IMAGE_EXTENSIONS.has(ext)) return 'image';
  if (VIDEO_EXTENSIONS.has(ext)) return 'video';
  if (AUDIO_EXTENSIONS.has(ext)) return 'audio';
  if (TEXT_EXTENSIONS.has(ext)) return 'text';
  if (CODE_EXTENSIONS.has(ext)) return 'code';
  if (PDF_EXTENSIONS.has(ext)) return 'pdf';
  return 'unsupported';
}

// ─── Sub-components ────────────────────────────────────────────────────────────

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

/**
 * Sub-component for rendering text-based previews.
 */
function PreviewText({ contentUrl }: { contentUrl: string }) {
  const [text, setText] = useState<string>("Loading content...");

  useEffect(() => {
    fetch(contentUrl)
      .then(res => res.text())
      .then(setText)
      .catch(() => setText("Failed to load text preview."));
  }, [contentUrl]);

  return <pre className="whitespace-pre-wrap">{text}</pre>;
}

// ─── Main Component ────────────────────────────────────────────────────────────

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
  getFileIcon,
  connectionMode = "relay",
  getAuthenticatedUrl,
}: PreviewModalProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [directStreamUrl, setDirectStreamUrl] = useState<string | null>(null);

  const ext = selectedFile?.name.split('.').pop()?.toLowerCase() || '';
  const mimeType = selectedFile?.mimeType || '';
  const category = useMemo(() => getPreviewCategory(ext, mimeType), [ext, mimeType]);
  const isSupported = SUPPORTED_PREVIEW_TYPES.includes(ext) || category !== 'unsupported';

  const buildFileContentEndpoint = useCallback((file: FileNode) => {
    const queryParams = new URLSearchParams({
      path: file.path || file.name,
    });
    return `/api/file-content?${queryParams.toString()}`;
  }, []);

  // Use direct browser URLs in HTTP modes so native media elements can stream/range-seek.
  const shouldDirectStream = useMemo(() => {
    if (category === 'unsupported') return false;
    return connectionMode === 'relay' || connectionMode === 'local';
  }, [category, connectionMode]);

  useEffect(() => {
    if (!selectedFile || selectedFile.isDirectory) {
      setObjectUrl(null);
      setDirectStreamUrl(null);
      setError(null);
      return;
    }

    // ─── VIDEO DIRECT STREAMING BYPASS ─────────────────────────────────
    // For videos in relay/local mode, set the direct URL instead of fetching a blob.
    // The browser's native <video> player handles HTTP Range Requests for smooth buffering.
    if (shouldDirectStream) {
      const endpoint = buildFileContentEndpoint(selectedFile);

      if (getAuthenticatedUrl) {
        setDirectStreamUrl(getAuthenticatedUrl(endpoint));
      } else {
        // Fallback: try to build the URL with available token
        const token = localStorage.getItem('cloud_storage_token') ||
                      localStorage.getItem('cloud_storage_android_token') ||
                      sessionStorage.getItem('node_session_token') || '';
        const separator = endpoint.includes('?') ? '&' : '?';
        setDirectStreamUrl(`${endpoint}${separator}token=${encodeURIComponent(token)}`);
      }
      setObjectUrl(null);
      setIsLoading(false);
      setError(null);
      return;
    }

    // ─── BLOB FETCH for images, PDFs, text, etc. ───────────────────────
    if (!isSupported) {
      setObjectUrl(null);
      setDirectStreamUrl(null);
      setError(null);
      return;
    }

    let isMounted = true;
    let currentUrl: string | null = null;

    const loadPreview = async () => {
      setIsLoading(true);
      setError(null);
      setDirectStreamUrl(null);

      try {
        const blob = await fetchFileBlob(selectedFile, apiFetch);

        if (isMounted) {
          const url = URL.createObjectURL(blob);
          currentUrl = url;
          setObjectUrl(url);
        }
      } catch (err: any) {
        if (isMounted) {
          setError(err.message || "Failed to load preview. Device might be offline.");
        }
      } finally {
        if (isMounted) {
          setIsLoading(false);
        }
      }
    };

    void loadPreview();

    return () => {
      isMounted = false;
      if (currentUrl) {
        URL.revokeObjectURL(currentUrl);
      }
    };
  }, [selectedFile, isSupported, shouldDirectStream, apiFetch, getAuthenticatedUrl, buildFileContentEndpoint]);

  // The effective URL for rendering (either a blob URL or a direct stream URL)
  const previewUrl = directStreamUrl || objectUrl;

  const handleDownload = useCallback(() => {
    if (!selectedFile) return;

    if (objectUrl) {
      const a = document.createElement('a');
      a.href = objectUrl;
      a.download = selectedFile.name;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      toast.success("Download started (from preview cache)");
    } else {
      toast.info("Preparing direct download...");
      let downloadUrl = selectedFile.isDirectory
        ? `/api/download-folder?path=${encodeURIComponent(selectedFile.path || selectedFile.name)}`
        : buildFileContentEndpoint(selectedFile);
      if (getAuthenticatedUrl) {
        downloadUrl = getAuthenticatedUrl(downloadUrl);
      } else {
        const token = localStorage.getItem('cloud_storage_token') ||
                      localStorage.getItem('cloud_storage_android_token') ||
                      sessionStorage.getItem('node_session_token') || '';
        downloadUrl += `${downloadUrl.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`;
      }
      const a = document.createElement('a');
      a.href = downloadUrl;
      a.download = selectedFile.isDirectory ? `${selectedFile.name}.zip` : selectedFile.name;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    }
  }, [selectedFile, objectUrl, getAuthenticatedUrl, buildFileContentEndpoint]);

  // ─── Render the preview content based on MIME category ───────────────

  const renderPreviewContent = () => {
    if (isLoading) {
      return (
        <div className="flex flex-col items-center gap-4">
          <SquareLoader size="lg" color="#2563EB" />
          <p className="text-[10px] font-bold text-[#4B5563] uppercase tracking-[0.2em] animate-pulse">
            {category === 'video' ? 'Buffering Stream...' : 'Streaming Data...'}
          </p>
        </div>
      );
    }

    if (error) {
      return (
        <div className="flex flex-col items-center gap-4 p-8 text-center animate-in fade-in zoom-in">
          <AlertCircle className="w-12 h-12 text-[#EF4444] opacity-50" />
          <p className="text-xs text-[#EF4444] font-medium leading-relaxed">{error}</p>
          <Button variant="outline" size="sm" className="mt-2 border-[#EF4444]/20 text-[#EF4444] hover:bg-[#EF4444]/10 h-8 text-[10px] font-bold uppercase tracking-widest" onClick={() => window.location.reload()}>
              Retry Connection
          </Button>
        </div>
      );
    }

    // ─── IMAGE ─────────────────────────────────────────────────────────
    if (category === 'image' && previewUrl) {
      return (
        <div className="w-full h-full relative flex items-center justify-center animate-in fade-in duration-700">
          <img
            src={previewUrl}
            className="max-w-full max-h-[80vh] object-contain rounded-lg p-4"
            alt={selectedFile?.name || "Preview"}
            onError={() => setError("Failed to render image. The file may be corrupt.")}
          />
        </div>
      );
    }

    // ─── VIDEO ─────────────────────────────────────────────────────────
    if (category === 'video' && previewUrl) {
      return (
        <div className="w-full h-full relative flex items-center justify-center animate-in fade-in duration-700">
          <video
            controls
            autoPlay={false}
            playsInline
            preload="metadata"
            src={previewUrl}
            className="max-w-full max-h-[80vh] rounded-lg border border-gray-700"
            onError={() => setError("Failed to play video. Format may not be supported by your browser.")}
          >
            Your browser does not support this video format.
          </video>
          {directStreamUrl && (
            <div className="absolute bottom-3 right-3">
              <Badge className="bg-emerald-500/20 text-emerald-400 border-emerald-500/30 text-[9px] font-bold uppercase tracking-widest">
                ⚡ Direct Stream
              </Badge>
            </div>
          )}
        </div>
      );
    }

    // ─── AUDIO ─────────────────────────────────────────────────────────
    if (category === 'audio' && previewUrl) {
      return (
        <div className="w-full h-full flex flex-col items-center justify-center gap-6 animate-in fade-in duration-700 p-8">
          <div className="w-20 h-20 bg-[#EC4899]/10 rounded-2xl flex items-center justify-center">
            <span className="text-3xl">🎵</span>
          </div>
          <p className="text-xs text-[#9CA3AF] font-medium">{selectedFile?.name}</p>
          <audio
            controls
            src={previewUrl}
            className="w-full max-w-[300px]"
            preload="metadata"
          />
        </div>
      );
    }

    // ─── PDF ───────────────────────────────────────────────────────────
    if (category === 'pdf' && previewUrl) {
      return (
        <div className="w-full h-full animate-in fade-in duration-700">
          <iframe
            src={previewUrl}
            className="w-full h-full bg-white rounded-2xl"
            title="PDF Preview"
          />
        </div>
      );
    }

    // ─── TEXT / CODE ───────────────────────────────────────────────────
    if ((category === 'text' || category === 'code') && previewUrl) {
      return (
        <div className="w-full h-full p-6 overflow-auto bg-[#0a0f18] font-mono text-[10px] text-[#9CA3AF] leading-relaxed scrollbar-hide animate-in fade-in duration-700">
          <PreviewText contentUrl={previewUrl} />
        </div>
      );
    }

    // ─── UNSUPPORTED FALLBACK ──────────────────────────────────────────
    if (!isSupported || category === 'unsupported') {
      return (
        <div className="flex flex-col items-center gap-6 p-8 text-center">
          <div className="relative">
            <div className="absolute inset-0 bg-[#2563EB]/20 blur-2xl rounded-full" />
            <FileQuestion className="w-16 h-16 text-[#4B5563] relative z-10" />
          </div>
          <div className="space-y-2 relative z-10">
            <h4 className="text-xs font-bold text-[#E5E7EB] uppercase tracking-[0.15em]">Preview Not Available</h4>
            <p className="text-[10px] text-[#4B5563] leading-relaxed max-w-[200px]">
              Native preview is not supported for <span className="text-[#9CA3AF] font-mono">.{ext}</span> files.
            </p>
          </div>
          <Button
            onClick={handleDownload}
            className="bg-[#111827] border border-[#1F2937] hover:bg-[#1F2937] text-white hover:text-[#2563EB] h-9 px-6 rounded-xl gap-2 font-bold text-[10px] uppercase tracking-widest transition-all shadow-xl"
          >
            <Download className="w-3.5 h-3.5" /> Download to View
          </Button>
        </div>
      );
    }

    // Fallback icon — no objectUrl yet (still at initial state before load starts)
    if (selectedFile) {
      return getFileIcon(selectedFile.name, selectedFile.isDirectory, "w-20 h-20 transition-transform group-hover:scale-110 duration-500 z-10 relative");
    }

    return null;
  };

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
            {renderPreviewContent()}
          </div>

          <div className="flex-1 overflow-y-auto space-y-8 pr-2 scrollbar-hide">
            <div>
              <h3 className="text-xl font-bold break-words pr-4 text-white leading-tight">{selectedFile.name}</h3>
              <div className="flex items-center gap-2 mt-2">
                 <Badge className="bg-[#2563EB]/10 text-[#2563EB] border-transparent text-[10px] uppercase font-bold tracking-widest">
                     {selectedFile.isDirectory ? "Folder" : ext.toUpperCase() || 'Unknown'}
                 </Badge>
                 {category !== 'unsupported' && category !== 'text' && (
                   <Badge className="bg-[#10B981]/10 text-[#10B981] border-transparent text-[10px] uppercase font-bold tracking-widest">
                     {category}
                   </Badge>
                 )}
                 <span className="text-[10px] text-[#4B5563] font-mono uppercase tracking-widest">{formatSize(selectedFile.size)}</span>
              </div>
            </div>

            <div className="space-y-5 pt-6 border-t border-[#1F2937]/50">
              <div className="grid grid-cols-2 gap-4">
                 <div className="space-y-1">
                    <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">Modified</label>
                    <p className="text-xs font-mono text-[#E5E7EB]">{formatDate(selectedFile.lastModified)}</p>
                 </div>
                 {category !== 'unsupported' && (
                   <div className="space-y-1">
                     <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">MIME Type</label>
                     <p className="text-xs font-mono text-[#E5E7EB]">{MIME_MAP[ext] || 'unknown'}</p>
                   </div>
                 )}
              </div>
              <div className="space-y-2">
                <label className="text-[9px] font-bold text-[#4B5563] uppercase tracking-widest block">Unified Path</label>
                <div className="flex items-center gap-2 px-3 py-2 bg-[#111827] border border-[#1F2937] rounded-xl flex-wrap">
                  <HardDrive className="w-3.5 h-3.5 text-[#2563EB] shrink-0" />
                  <p className="text-[10px] font-mono text-[#9CA3AF] break-all">/Storage/{selectedFile.path || "root"}</p>
                </div>
              </div>
            </div>

            <div className="pt-8 space-y-3 pb-4">
              <Button
                className="w-full bg-[#2563EB] hover:bg-[#1d4ed8] h-11 rounded-xl gap-2 font-bold shadow-lg shadow-blue-500/10 active:scale-95 transition-all"
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
          <h3 className="text-sm font-bold uppercase tracking-widest text-[#E5E7EB]">Vault Details</h3>
          <p className="text-xs mt-3 leading-relaxed">Select a file to inspect metadata and generated previews</p>
        </div>
      )}
    </AnimatePresence>
  );
}
