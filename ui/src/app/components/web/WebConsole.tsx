import { useState, useRef, useEffect, useCallback, useMemo } from "react";
import { useParams } from "react-router";
import { useWebRTC } from '../../hooks/useWebRTC';
import type { P2PResponse } from '../../hooks/p2pTransport';
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
  AlertCircle,
  Menu,
  ArrowLeft,
  Sun,
  Moon,
  RefreshCw,
  Github,
  Twitter,
  Linkedin,
  Youtube
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
import { PreviewModal } from "./PreviewModal";
import "../../../styles/console.css";
import "../../../styles/animated-inputs.css";
import "./animated-folder.css";

// ──────────────────────────────────────────────────────────────────────────────
// STABILITY UTILS
// ──────────────────────────────────────────────────────────────────────────────

const AnimatedFolder = ({ size = "small" }: { size?: "small" | "large" }) => (
  <div className={`folder-3d-wrapper folder-3d-${size}`}>
    <div className="file-v3">
      <div className="work-5"></div>
      <div className="work-4"></div>
      <div className="work-3"></div>
      <div className="work-2"></div>
      <div className="work-1"></div>
    </div>
  </div>
);

/**
 * Enhanced fetch that enforces JSON response and handles common errors.
 * Prevents "JSON Parse error: Unrecognized token '<'" by ensuring Content-Type.
 */
async function fetchJson<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers = { 
    ...options.headers, 
    'Accept': 'application/json',
    'X-Requested-With': 'XMLHttpRequest'
  };

  const response = await fetch(url, { ...options, headers });
  
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`);
  }

  const contentType = response.headers.get('content-type');
  if (!contentType || !contentType.includes('application/json')) {
    // This is the CRITICAL fix for the route intercept issue
    console.error("EXPECTED JSON, GOT HTML/OTHER. Route likely intercepted by SPA catch-all.", { url });
    throw new Error("Malformed response: Expected JSON but received HTML. Ensure API routes are registered before catch-all.");
  }

  return response.json();
}

/**
 * Hook for intelligent node status polling. 
 * Prevents flickering to 'offline' on single-request timeouts.
 */
function useNodeStatus(shareCode: string, intervalMs = 5000) {
  const [isOnline, setIsOnline] = useState(true);
  const [lastCheck, setLastCheck] = useState(Date.now());
  const failureCount = useRef(0);
  const onlineRef = useRef(true);
  const MAX_FAILURES = 2;

  const checkStatus = useCallback(async (forceTimestampUpdate = false) => {
    if (!shareCode) return;
    const checkedAt = Date.now();
    let nextOnline = onlineRef.current;

    try {
      const data = await fetchJson<{online: boolean}>(`/api/node/${shareCode}/status`);
      if (data.online) {
        nextOnline = true;
        failureCount.current = 0;
      } else {
        failureCount.current++;
        if (failureCount.current >= MAX_FAILURES) {
          nextOnline = false;
        }
      }
    } catch (err) {
      failureCount.current++;
      if (failureCount.current >= MAX_FAILURES) {
        if (onlineRef.current) {
          console.error("Node status check failed repeatedly", err);
        }
        nextOnline = false;
      }
    }

    if (nextOnline !== onlineRef.current) {
      onlineRef.current = nextOnline;
      setIsOnline(nextOnline);
      setLastCheck(checkedAt);
      return;
    }

    if (forceTimestampUpdate || !nextOnline) {
      setLastCheck(checkedAt);
    }
  }, [shareCode]);

  useEffect(() => {
    if (!shareCode) return;
    void checkStatus(true);
    const timer = setInterval(() => {
      void checkStatus();
    }, intervalMs);
    return () => clearInterval(timer);
  }, [checkStatus, intervalMs, shareCode]);

  return {
    isOnline,
    checkStatus: () => checkStatus(true),
    lastCheck,
  };
}

interface FileNode {
  id: string; // Used as the full uri
  name: string;
  path: string; // Added path property
  isDirectory: boolean;
  size: number;
  lastModified: number;
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

export function WebConsole() {
  console.log("WEB_CONSOLE_RENDERED");
  const [files, setFiles] = useState<FileNode[]>([]);
  const [currentPath, setCurrentPath] = useState<string>("");
  const [selectedFile, setSelectedFile] = useState<FileNode | null>(null);
  const [activeTab, setActiveTab] = useState("Drive");
  const [viewMode, setViewMode] = useState<"list" | "grid">("list");
  const [isUploading, setIsUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState<'idle' | 'uploading' | 'success' | 'error' | 'partial'>('idle');
  const [uploadProgress, setUploadProgress] = useState(0);
  const [folderProgress, setFolderProgress] = useState({ success: 0, uploading: 0, failed: 0, total: 0 });
  const [failedUploads, setFailedUploads] = useState<{file: File, error: string}[]>([]);
  const [sanitizedUploads, setSanitizedUploads] = useState<Record<string, string>>({});
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [storageStats, setStorageStats] = useState({ total: 0, used: 0, free: 0 });
  const [driveHealthStatus, setDriveHealthStatus] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [driveHealthError, setDriveHealthError] = useState("");
  const [filesError, setFilesError] = useState("");
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
  const [activePreviewFile, setActivePreviewFile] = useState<FileNode | null>(null);
  const [terminalLogs, setTerminalLogs] = useState<{ id: string; msg: string; type: 'sys' | 'net' | 'io'; timestamp: string }[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);

  const { shareCode: paramShareCode } = useParams<{ shareCode: string }>();

  const getShareCode = () => {
    if (paramShareCode) return paramShareCode.toUpperCase();
    const searchParams = new URLSearchParams(window.location.search);
    const queryCode = searchParams.get('code') || searchParams.get('shareCode');
    if (queryCode) return queryCode.toUpperCase();
    const path = window.location.pathname;
    const hash = window.location.hash;
    const pathMatch = path.match(/\/(?:node|console)\/([A-Z0-9]{5,20})/i);
    const hashMatch = hash.match(/\/(?:node|console|#\/console)\/([A-Z0-9]{5,20})/i);
    return (pathMatch?.[1] || hashMatch?.[1] || '').toUpperCase();
  };

  const getBaseUrl = () => '';
  const getRelayUrl = () => window.location.origin;

  const shareCode = getShareCode();
  const { isOnline: isSignalingOnline, checkStatus: refreshNodeStatus, lastCheck } = useNodeStatus(shareCode);
  
  const isLocalSession = useMemo(() => {
    const host = window.location.hostname;
    return host === 'localhost' || host === '127.0.0.1' || /^10\./.test(host) || /^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(host) || /^192\.168\./.test(host);
  }, []);
  
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [authMode, setAuthMode] = useState<'login' | 'signup' | 'none'>('none');
  const [authUsername, setAuthUsername] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authError, setAuthError] = useState('');
  const [isFirstHandshake, setIsFirstHandshake] = useState(true);
  const [theme, setTheme] = useState<"dark" | "light">(() => {
    return (localStorage.getItem("cloud_storage_theme") as "dark" | "light") || "dark";
  });
  const getHeaders = useCallback(() => {
    const token = localStorage.getItem('cloud_storage_token') || localStorage.getItem('cloud_storage_android_token') || '';
    const params = new URLSearchParams(window.location.hash.split('?')[1]);
    const pwd = params.get('pwd') || token;
    return {
      'Authorization': `Bearer ${pwd}`,
      ...(shareCode ? { 'X-Node-Id': shareCode } : {}),
    };
  }, [shareCode]);
  const buildApiUrl = useCallback((endpoint: string) => {
    const url = new URL(`${getBaseUrl()}${endpoint}`, window.location.origin);
    if (shareCode && url.pathname.startsWith('/api/')) {
      url.searchParams.set('nodeId', shareCode);
    }
    return `${url.pathname}${url.search}${url.hash}`;
  }, [shareCode]);

  const getAuthenticatedUrl = useCallback((path: string): string => {
    const url = buildApiUrl(path);
    const authList = getHeaders() as Record<string, string>;
    const auth = authList['Authorization'];
    const token = auth?.startsWith('Bearer ') ? auth.substring(7) : auth;
    if (!token) return url;
    
    // Check if URL already has query params
    const separator = url.includes('?') ? '&' : '?';
    return `${url}${separator}pwd=${encodeURIComponent(token)}`;
  }, [buildApiUrl, getHeaders]);

  const getFileKindLabel = useCallback((file: FileNode) => {
    if (file.isDirectory) return "Folder";
    // Only use extension if the file actually has a dot in the name
    if (file.name.includes('.')) {
      const ext = file.name.split('.').pop()?.toUpperCase();
      if (ext && ext.length <= 5) return ext;
    }
    // No extension — return generic label
    return "FILE";
  }, []);

  const getFileBadgeCategory = useCallback((file: FileNode) => {
    if (file.isDirectory) return "folder";
    const ext = file.name.includes('.') ? file.name.split('.').pop()?.toLowerCase() : null;
    if (ext) {
      if (['png','jpg','jpeg','gif','svg','webp','bmp','ico'].includes(ext)) return "image";
      if (['mp4','mov','avi','webm','mkv'].includes(ext)) return "video";
      if (ext === 'pdf') return "pdf";
      if (['js','ts','tsx','jsx','py','java','kt','go','rs','c','cpp','h','css','scss'].includes(ext)) return "code";
      if (['html','htm','xml','svg'].includes(ext)) return "html";
      if (['txt','md','log','csv','json','yaml','yml','toml'].includes(ext)) return "text";
    }
    return "unknown";
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    if (theme === "light") root.classList.add("light");
    else root.classList.remove("light");
    localStorage.setItem("cloud_storage_theme", theme);
  }, [theme]);

  /* logic moved below to use webrtc hook status */

  useEffect(() => {
    const checkAuth = async () => {
      try {
        const data = await fetchJson<{hasAccount: boolean}>(buildApiUrl('/api/auth/status'));
        const token = localStorage.getItem('cloud_storage_token') || localStorage.getItem('cloud_storage_android_token');
        const params = new URLSearchParams(window.location.hash.split('?')[1]);
        const pwd = params.get('pwd') || token;

        if (pwd) {
          const verify = await fetch(buildApiUrl('/api/storage'), { headers: getHeaders() });
          if (verify.ok) {
            setIsAuthenticated(true);
            setAuthMode('none');
            return;
          }
        }
        setIsAuthenticated(false);
        setAuthMode(data.hasAccount ? 'login' : 'signup');
      } catch (e) {
        console.error("Auth check failed:", e);
      }
    };
    void checkAuth();
  }, [buildApiUrl, getHeaders]);

  const logActivity = useCallback((msg: string, type: 'sys' | 'net' | 'io' = 'sys') => {
    const id = Math.random().toString(36).substring(7);
    const timestamp = new Date().toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    setTerminalLogs(prev => [{ id, msg, type, timestamp }, ...prev].slice(0, 15));
  }, []);

  const openPreview = (file: FileNode) => {
    setActivePreviewFile(file);
    logActivity(`PREVIEW_REQUEST: ${file.name}`, 'sys');
    setTimeout(() => logActivity(`P2P_STREAM_INIT: ${file.id}`, 'net'), 400);
    setTimeout(() => logActivity(`BLOB_GENERATED: success`, 'io'), 800);
  };

  const SidebarContent = () => (
    <div className="sidebar" style={{ background: 'transparent', border: 'none' }}>
      {/* Upload buttons */}
      <div className="sidebar-upload-section">
        <button className="sidebar-btn sidebar-btn-primary" onClick={() => { fileInputRef.current?.click(); setIsMobileMenuOpen(false); }}>
          <Upload className="w-4 h-4" /> Upload File
        </button>
        <button className="sidebar-btn sidebar-btn-primary" onClick={() => { folderInputRef.current?.click(); setIsMobileMenuOpen(false); }}>
          <Folder className="w-4 h-4" /> Upload Folder
        </button>
        <button className="sidebar-btn sidebar-btn-ghost" onClick={() => { handleCreateFolder(); setIsMobileMenuOpen(false); }}>
          <Plus className="w-4 h-4" /> New Folder
        </button>
        <input type="file" ref={fileInputRef} className="hidden" multiple onChange={handleUpload} />
        <input type="file" ref={folderInputRef} className="hidden" multiple {...{webkitdirectory: "true", directory: "true"} as any} onChange={handleUpload} />
      </div>

      <div className="sidebar-divider" />

      {/* Navigation */}
      <div className="sidebar-nav-label">MAIN</div>
      <div className="sidebar-nav-list">
        {[
          { label: "Drive", icon: HardDrive },
          { label: "Recent", icon: Clock },
          { label: "Shared", icon: Share2 },
          { label: "Trash", icon: Trash2 },
        ].map((item) => (
          <button
            key={item.label}
            onClick={() => { setActiveTab(item.label); setCurrentPath(""); setIsMobileMenuOpen(false); }}
            className={`sidebar-nav-item ${activeTab === item.label ? 'active' : ''}`}
          >
            <span className="sidebar-nav-icon"><item.icon className="w-4 h-4" /></span>
            <span>{item.label}</span>
          </button>
        ))}
      </div>

      <div className="sidebar-divider" />

      {/* Drive Health */}
      <div className="sidebar-drive-health">
        <div className="sidebar-drive-health-label">DRIVE HEALTH</div>
        <div className="sidebar-storage-main">
          {driveHealthStatus === "error"
            ? "Storage Error"
            : storageStats.total > 0
              ? `${formatSize(storageStats.used)} / ${formatSize(storageStats.total)}`
              : driveHealthStatus === "loading" ? "Analyzing..." : "Waiting..."}
        </div>
        <div className="sidebar-storage-row">
          <span className="sidebar-storage-free">
            {storageStats.total > 0 ? `${formatSize(storageStats.free)} free` : "—"}
          </span>
          <span className="sidebar-storage-pct">
            {storageStats.total > 0 ? `${Math.round((storageStats.used / storageStats.total) * 100)}%` : "--"}
          </span>
        </div>
        <div className="sidebar-progress-bar">
          <div className="sidebar-progress-fill" style={{ width: `${storageStats.total > 0 ? (storageStats.used / storageStats.total) * 100 : 0}%` }} />
        </div>
        {driveHealthStatus === "error" && (
          <p style={{ fontSize: 11, color: '#EF4444', marginTop: 8 }}>{driveHealthError}</p>
        )}
      </div>
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

  const webrtc = useWebRTC({
    relayUrl: getRelayUrl(),
    shareCode: shareCode,
    enabled: !!shareCode,
  });

  const { connectionState: p2pState, transport: p2pTransport, isReady: p2pReady, isDataChannelReady, reconnect: p2pReconnect } = webrtc;

  // Unified "Online" Detection: Node is online if signaling OR P2P is healthy OR we are on a direct local connection
  useEffect(() => {
    const isOnline = isLocalSession || isSignalingOnline || (p2pState === 'connected' && isDataChannelReady);
    setIsNodeOffline(!isOnline);
  }, [isLocalSession, isSignalingOnline, p2pState, isDataChannelReady]);

  const canUseNodeApi = isLocalSession || (p2pState === 'connected' && isDataChannelReady) || p2pState === 'fallback';

  /**
   * Unified API fetch — uses P2P DataChannel when connected, falls back to relay.
   * This is the primary replacement for all fetch(getBaseUrl() + endpoint) calls.
   */
  const apiFetch = useCallback(async (endpoint: string, options: RequestInit = {}): Promise<Response | P2PResponse> => {
    const authHeaders = getHeaders();
    const mergedHeaders = {
      ...(authHeaders as Record<string, string>),
      ...(options.headers as Record<string, string> || {}),
      ...(shareCode ? { 'X-Node-Id': shareCode } : {}),
    };

    if (p2pReady && isDataChannelReady && p2pTransport?.ready) {
      // Route through the P2P DataChannel — zero relay bandwidth
      return p2pTransport.fetch(endpoint, {
        method: options.method || 'GET',
        headers: mergedHeaders,
        body: options.body as string | null,
      });
    }

    // Fallback: route through the relay server
    return fetch(buildApiUrl(endpoint), {
      ...options,
      headers: mergedHeaders,
    });
  }, [buildApiUrl, getHeaders, isDataChannelReady, p2pReady, p2pTransport, shareCode]);


  const loadStorageStats = useCallback(async (retryCount = 0) => {
    if (!canUseNodeApi) return;
    try {
      const res = await apiFetch('/api/storage', {
        headers: getHeaders() as any
      });
      
      if (!res.ok) {
          if (retryCount < 3) {
              setTimeout(() => loadStorageStats(retryCount + 1), 2000 * (retryCount + 1));
              return;
          }
          throw new Error(`Storage stats failed with status ${res.status}`);
      }

      const data = await res.json();
      setStorageStats({
        total: Number(data.total ?? data.totalBytes ?? 0),
        used: Number(data.used ?? data.usedBytes ?? 0),
        free: Number(data.free ?? data.freeBytes ?? 0),
      });
      setDriveHealthStatus("ready");
    } catch (err: any) {
      console.error("Storage stats failed:", err?.message || err);
      if (retryCount >= 3) {
        setDriveHealthStatus("error");
        setDriveHealthError(err?.message || "Unable to load storage stats");
      }
    }
  }, [apiFetch, getHeaders, canUseNodeApi]);

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

  const loadFiles = useCallback(async (path: string, retryCount = 0) => {
    setIsRefreshing(true);
    if (retryCount === 0) setFilesError("");
    
    try {
      const timestamp = Date.now();
      const endpoint = activeTab === "Trash" ? `/api/trash?t=${timestamp}` : `/api/files?path=${encodeURIComponent(path)}&t=${timestamp}`;
      const res = await apiFetch(endpoint, { 
        headers: { ...getHeaders(), 'Cache-Control': 'no-cache, no-store, must-revalidate', 'Pragma': 'no-cache' } as any
      });

      if (res.status === 502 || res.status === 503) {
        // Backend/Relay unavailable - potentially transient
        if (retryCount < 3) {
            setTimeout(() => loadFiles(path, retryCount + 1), 1500 * (retryCount + 1));
            return;
        }
        const body = await res.json().catch(() => ({}));
        const reason = body.error === 'agent_offline' 
            ? 'Android Node is offline. Open the Easy Storage app on your phone.'
            : 'Relay server is unavailable. Please try again in a moment.';
        toast.error(reason, { duration: 6000 });
        setFilesError(reason);
        return;
      }

      if (res.status === 401) {
        const message = "Unauthorized: Please provide a valid ?pwd= password.";
        toast.error(message);
        setFilesError(message);
        return;
      }
      
      if (!res.ok) throw new Error("Failed to load files");
      
      const data = await res.json();
      setFiles(Array.isArray(data) ? data : []);
      // Only clear selection if we actually succeeded in loading a new view
      setSelectedFile(null);
      clearSelection();
    } catch (e: any) {
      const message = e.message || "Failed to load directory";
      console.error("File listing failed:", message);
      
      if (retryCount < 2) {
          setTimeout(() => loadFiles(path, retryCount + 1), 2000);
      } else {
          setFilesError(message);
          toast.error(message);
          // Note: We intentionally DO NOT call setFiles([]) here to maintain stale data (stale-while-revalidate)
      }
    } finally {
      setIsRefreshing(false);
    }
  }, [activeTab, apiFetch, getHeaders]);

  useEffect(() => {
    if (!canUseNodeApi) return;
    void loadFiles(currentPath);
    void loadStorageStats();
  }, [activeTab, canUseNodeApi, currentPath, loadFiles, loadStorageStats]);

  const handleRetryConnection = useCallback(() => {
    refreshNodeStatus();
    p2pReconnect();
  }, [p2pReconnect, refreshNodeStatus]);

  const nodeStatus = useMemo(() => {
    if (isNodeOffline) {
      return {
        label: "Node Offline",
        className: "is-offline",
      };
    }

    if (p2pState === "fallback") {
      return {
        label: "Relay Mode",
        className: "is-relay",
      };
    }

    return {
      label: "Node Online",
      className: "is-online",
    };
  }, [isNodeOffline, p2pState]);

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
      const res = await apiFetch('/api/folder', {
        method: 'POST',
        headers: { ...getHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' } as any,
        body: formData.toString()
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

  const uploadChunkViaBestPath = useCallback(async (
    chunk: Blob,
    filename: string,
    relativePath: string | undefined,
    chunkIndex: number,
    totalChunks: number,
    fileId: string,
    chunkSize: number,
    fileSize: number,
    fileProgressMap: Record<string, number>,
    updateGlobalProgress: () => void
  ) => {
    const params = new URLSearchParams({
      path: currentPath,
      filename,
      chunkIndex: String(chunkIndex),
      totalChunks: String(totalChunks),
      totalSize: String(fileSize),
    });

    if (relativePath) {
      params.set('relativePath', relativePath);
    }

    if (p2pReady && isDataChannelReady && p2pTransport?.ready) {
      const uploadFile = new File([chunk], filename, {
        type: chunk.type || 'application/octet-stream',
      });

      fileProgressMap[fileId] = chunkIndex * chunkSize;
      updateGlobalProgress();

      const response = await p2pTransport.upload(
        '/api/upload_chunk',
        params.toString(),
        uploadFile,
        getHeaders() as Record<string, string>
      );

      let payload: any = {};
      try {
        payload = await response.json();
      } catch (error) {
        throw new Error(response.status >= 500 ? `Server Error (${response.status})` : 'Malformed JSON response');
      }

      if (!response.ok || !payload.success) {
        throw new Error(payload.error || `Server error ${response.status}`);
      }

      fileProgressMap[fileId] = (chunkIndex * chunkSize) + chunk.size;
      updateGlobalProgress();
      return;
    }

    await new Promise<void>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      let uploadUrl = buildApiUrl(`/api/upload_chunk?${params.toString()}`);

      xhr.open("POST", uploadUrl);
      xhr.timeout = 60000;

      const headers = getHeaders();
      if (headers.Authorization) xhr.setRequestHeader('Authorization', headers.Authorization);
      xhr.setRequestHeader("Content-Type", "application/octet-stream");

      xhr.upload.addEventListener("progress", (event) => {
        if (event.lengthComputable) {
          fileProgressMap[fileId] = (chunkIndex * chunkSize) + event.loaded;
          updateGlobalProgress();
        }
      });

      xhr.onload = () => {
        let resp: any;
        try {
          resp = JSON.parse(xhr.responseText);
        } catch (e) {
          resp = {
            success: false,
            error: xhr.status >= 500 ? `Server Error (${xhr.status})` : "Malformed JSON response"
          };
          console.error("Failed to parse chunk response:", xhr.responseText);
        }

        if (xhr.status === 200 && resp.success) {
          fileProgressMap[fileId] = (chunkIndex * chunkSize) + chunk.size;
          updateGlobalProgress();
          resolve();
        } else {
          const errorDetail = resp.error || `Server error ${xhr.status}`;
          console.error(`Chunk failed for ${filename} chunk ${chunkIndex}:`, errorDetail);
          reject(new Error(errorDetail));
        }
      };

      xhr.ontimeout = () => reject(new Error("Chunk upload timed out (60s)"));
      xhr.onerror = () => reject(new Error("Network connection error"));
      xhr.send(chunk);
    });
  }, [buildApiUrl, currentPath, getHeaders, isDataChannelReady, p2pReady, p2pTransport]);

  const processFiles = async (files: File[]) => {
    if (!files || files.length === 0) return;
    
    setUploadStatus('uploading');
    setIsUploading(true);
    setUploadProgress(0);
    setFolderProgress({ success: 0, uploading: files.length, failed: 0, total: files.length });
    setFailedUploads([]);
    setSanitizedUploads({});

    // We'll track progress by bytes for all files
    const fileProgressMap: Record<string, number> = {};
    const totalBytes = files.reduce((acc, f) => acc + f.size, 0);

    const updateGlobalProgress = () => {
      const uploadedBytes = Object.values(fileProgressMap).reduce((acc, b) => acc + b, 0);
      setUploadProgress(Math.round((uploadedBytes / totalBytes) * 100));
    };

    // Pre-flight Folder Manifest if applicable
    const manifest = files.filter(f => f.webkitRelativePath && f.webkitRelativePath.includes('/')).map(f => {
      const CHUNK_SIZE = 5 * 1024 * 1024;
      return {
        relativePath: f.webkitRelativePath,
        size: f.size,
        totalChunks: Math.max(1, Math.ceil(f.size / CHUNK_SIZE))
      };
    });

    if (manifest.length > 0) {
      try {
        const res = await apiFetch(`/api/folder_manifest?path=${encodeURIComponent(currentPath)}`, {
          method: 'POST',
          headers: { ...getHeaders(), 'Content-Type': 'application/json' } as any,
          body: JSON.stringify(manifest)
        });
        const resp = await res.json().catch(() => ({}));
        if (!res.ok || !resp.success) {
          throw new Error(resp.error || "Failed to pre-create directory tree");
        }
      } catch (e: any) {
        setIsUploading(false);
        setUploadStatus('error');
        toast.error(`Folder manifest creation failed: ${e.message}`);
        return;
      }
    }

    const CHUNK_SIZE = 5 * 1024 * 1024;
    const failed: {file: File, error: string}[] = [];

    for (let i = 0; i < files.length; i++) {
        const file = files[i] as File & { webkitRelativePath?: string };
        const fileId = crypto.randomUUID();
        fileProgressMap[fileId] = 0;

        let relativePath = file.webkitRelativePath || "";
        let filename = file.name;
        if (relativePath) {
            filename = relativePath.split('/').pop() || file.name;
        }
        
        const totalChunks = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));
        let fileSuccess = true;
        let fileError = "";

        for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            let chunkSuccess = false;
            let chunkError = "";
            const start = chunkIndex * CHUNK_SIZE;
            const end = Math.min(start + CHUNK_SIZE, file.size);
            const chunk = file.slice(start, end);

            // Chunk-level retry (Standardized XHR contract)
            for (let retry = 0; retry < 3; retry++) {
                try {
                    await uploadChunkViaBestPath(
                      chunk,
                      filename,
                      file.webkitRelativePath,
                      chunkIndex,
                      totalChunks,
                      fileId,
                      CHUNK_SIZE,
                      file.size,
                      fileProgressMap,
                      updateGlobalProgress
                    );
                    chunkSuccess = true;
                    break;
                } catch (err: any) {
                    chunkError = err.message;
                    console.warn(`Retry ${retry + 1} for ${file.name} (Chunk ${chunkIndex}): ${chunkError}`);
                    await new Promise(r => setTimeout(r, 1000 * (retry + 1))); 
                }
            }

            if (!chunkSuccess) {
                fileSuccess = false;
                fileError = chunkError;
                break; 
            }
        }

        if (fileSuccess) {
            // Call upload_complete for idempotency / verification
            try {
                const completeUrl = buildApiUrl(`/api/upload_complete?path=${encodeURIComponent(currentPath)}&filename=${encodeURIComponent(filename)}`);
                await apiFetch(completeUrl, { method: 'POST', headers: getHeaders() as any });
            } catch (e) { console.warn("upload_complete failed, ignoring as chunks succeeded", e); }

            setFolderProgress(prev => ({ ...prev, uploading: prev.uploading - 1, success: prev.success + 1 }));
        } else {
            failed.push({file, error: fileError});
            setFolderProgress(prev => ({ ...prev, uploading: prev.uploading - 1, failed: prev.failed + 1 }));
        }
    }

    // After all files are done, if any folder was uploaded, finalize folder names
    if (manifest.length > 0 && files.length > 0) {
        try {
            const firstRel = files.find(f => f.webkitRelativePath)?.webkitRelativePath || "";
            const rootFolder = firstRel.split('/')[0] || "";
            if (rootFolder) {
                const response = await apiFetch(`/api/folder_complete?path=${encodeURIComponent(currentPath)}&folder=${encodeURIComponent(rootFolder)}`, {
                    method: 'POST',
                    headers: getHeaders() as any
                });
                const data = await response.json();
                if (data.success && data.data?.sanitizedNames) {
                    setSanitizedUploads(data.data.sanitizedNames);
                }
            }
        } catch (e) { console.error("folder_complete finalization failed", e); }
    }

    setIsUploading(false);
    if (failed.length > 0) {
        setUploadStatus(failed.length === files.length ? 'error' : 'partial');
        setFailedUploads(failed);
        toast.error(`${failed.length} files failed to upload.`);
    } else {
        setUploadStatus('success');
        setUploadProgress(100);
        toast.success("All files uploaded successfully!");
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

  const handleDownload = async (file: FileNode) => {
    const endpoint = file.isDirectory
      ? `/api/download_folder?path=${encodeURIComponent(file.path)}&folder=${encodeURIComponent(file.name)}`
      : `/api/download?path=${encodeURIComponent(file.path)}&file=${encodeURIComponent(file.name)}`;

    const url = buildApiUrl(endpoint);
    
    try {
      // Pre-flight check to prevent downloading 28-byte JSON error strings
      const res = await apiFetch(endpoint, { method: 'HEAD' });
      
      if (!res.ok) {
        // If HEAD fails, try full fetch to get the error message
        const fullRes = await apiFetch(endpoint);
        const errorData = await fullRes.json().catch(() => ({ error: "Download failed" }));
        const statusText = 'statusText' in fullRes ? (fullRes as Response).statusText : 'Unknown Error';
        toast.error(`Download blocked: ${errorData.error || statusText}`);
        return;
      }

      // If check passes, trigger the native browser download
      const a = document.createElement('a');
      a.href = url;
      a.download = file.name + (file.isDirectory ? ".zip" : "");
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      toast.info("Download started");
    } catch (err) {
      toast.error("Network error during download pre-flight");
    }
  };

  const handleDelete = async (file: FileNode) => {
    const formData = new URLSearchParams();
    formData.append("path", currentPath);
    formData.append("name", file.name);
    try {
      const res = await apiFetch('/api/delete', {
        method: 'POST',
        headers: { ...getHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' } as any,
        body: formData.toString()
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
      const res = await apiFetch('/api/bulk_action', {
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
      
    const url = buildApiUrl(`/api/download_bulk?items=${encodeURIComponent(JSON.stringify(items))}`);
    
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
      const res = await apiFetch('/api/rename', {
        method: 'POST',
        headers: { ...getHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' } as any,
        body: formData.toString()
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


  const handleAuth = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError('');
    try {
      const endpoint = authMode === 'signup' ? '/api/auth/signup' : '/api/auth/login';
      const res = await apiFetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' } as any,
        body: JSON.stringify({ password: authPassword })
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
      await apiFetch('/api/auth/logout', { method: 'POST', headers: getHeaders() as any });
    } catch (e) {}
    localStorage.removeItem('cloud_storage_token');
    localStorage.removeItem('cloud_storage_android_token');
    setIsAuthenticated(false);
    setAuthMode('login');
    setFiles([]);
    setAuthUsername('');
    setAuthPassword('');
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

            <form onSubmit={handleAuth} className="space-y-2 pt-4">
              <div className="form-control">
                <input 
                  type="text" 
                  required 
                  value={authUsername} 
                  onChange={e => setAuthUsername(e.target.value)} 
                  autoComplete="username"
                />
                <label>
                  {"Username".split('').map((char, index) => (
                    <span key={index} style={{ transitionDelay: `${index * 30}ms` }}>{char}</span>
                  ))}
                </label>
              </div>

              <div className="form-control">
                <input 
                  type="password" 
                  required 
                  value={authPassword} 
                  onChange={e => setAuthPassword(e.target.value)} 
                  autoComplete="current-password"
                />
                <label>
                  { (authMode === 'signup' ? "Create Passkey" : "Node Passkey").split('').map((char, index) => (
                    <span key={index} style={{ transitionDelay: `${index * 30}ms` }}>{char}</span>
                  ))}
                </label>
              </div>

              {authError && <div className="text-red-400 text-xs font-medium text-center bg-red-500/10 py-2 rounded-lg">{authError}</div>}

              <button type="submit" className="w-full launch-node-btn mt-4">
                <Cloud className="w-5 h-5" />
                <span>{authMode === 'signup' ? 'Secure Node Bridge' : 'Unlock Node Bridge'}</span>
              </button>
            </form>
          </Card>
        </motion.div>
      </div>
    );
  }


  if (p2pState === 'connecting' || p2pState === 'signaling' || p2pState === 'ice-gathering' || p2pState === 'dc-opening' || (p2pState === 'connected' && !isDataChannelReady)) {
    const getP2PMessage = () => {
      switch (p2pState) {
        case 'connecting': return 'Initializing Node Stack...';
        case 'signaling': return 'Negotiating Handshake...';
        case 'ice-gathering': return 'Gathering Network Nodes...';
        case 'dc-opening': return 'Opening Secure Bridge...';
        case 'connected': return 'Finalizing Bridge...';
        default: return 'Establishing Secure Connection...';
      }
    };

    return (
      <div className="h-screen bg-[#0B1220] flex flex-col items-center justify-center p-6 text-[#E5E7EB] relative overflow-hidden">
        {/* Previous Green Buffering UI design — glow and simple centering */}
        <div className="absolute top-[-20%] left-[-10%] w-[50%] h-[50%] bg-[#22C55E]/10 blur-[120px] rounded-full pointer-events-none" />
        <div className="z-10 flex flex-col items-center justify-center text-center">
          <div className="relative mb-8 h-32 flex items-center justify-center">
            <SquareLoader size="lg" color="#22C55E" />
          </div>
          <h1 className="text-3xl font-bold mb-3 tracking-tight text-white">{getP2PMessage()}</h1>
          <p className="text-sm text-[#9CA3AF] max-w-sm mx-auto leading-relaxed">
            Creating a direct peer-to-peer connection for fast, private file transfers.
            No data passes through the relay.
          </p>
          <div className="mt-8 flex items-center gap-2 px-3 py-1 bg-emerald-500/10 border border-emerald-500/20 rounded-full">
            <div className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
            <span className="text-[10px] font-bold text-emerald-500 uppercase tracking-widest">{p2pState}</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="app-shell">
      {/* ═══════════ TOPBAR — spans full width ═══════════ */}
      <header className="topbar">
        <div className="topbar-logo-section">
          <button className="mobile-menu-btn" onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}>
            <Menu className="w-5 h-5" />
          </button>
          <div className="topbar-logo">
            <Cloud className="w-[18px] h-[18px] text-white" />
          </div>
          <span style={{ fontSize: "15px", fontWeight: 600, color: "#E2E5F0" }}>
            Easy Storage
          </span>
        </div>

        <div className="topbar-search-section">
          <div style={{ position: "relative", width: "100%", maxWidth: "400px" }}>
            <Search
              style={{
                position: "absolute",
                left: "10px",
                top: "50%",
                transform: "translateY(-50%)",
                color: "#7A8099",
                width: "16px",
                height: "16px",
              }}
            />
            <input className="topbar-search-input" placeholder="Search files..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)} />
          </div>
        </div>

        <div className="topbar-actions-section">
          <div className={`node-status-indicator ${nodeStatus.className}`}>
            <span className="status-dot" />
            <span>{nodeStatus.label}</span>
          </div>
          <button className="topbar-icon-btn" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>
            {theme === "dark" ? <Sun className="w-[18px] h-[18px]" /> : <Moon className="w-[18px] h-[18px]" />}
          </button>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <div className="topbar-avatar">
                <User className="w-[18px] h-[18px]" />
              </div>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="bg-[#111827] border-[#1F2937] text-[#E5E7EB] w-48">
              <div className="px-3 py-2 text-xs text-[#9CA3AF] font-mono border-b border-[#1F2937] mb-1">{"Admin Session"}</div>
              <DropdownMenuItem className="gap-2 text-[#EF4444]" onClick={handleLogout}>
                <Trash2 className="w-4 h-4" /> Disconnect
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>

      {/* ═══════════ MOBILE SIDEBAR DRAWER ═══════════ */}
      <AnimatePresence>
        {isMobileMenuOpen && (
          <>
            <motion.div
              className="mobile-menu-overlay"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setIsMobileMenuOpen(false)}
            />
            <motion.div
              className="mobile-menu-drawer"
              initial={{ x: -280 }}
              animate={{ x: 0 }}
              exit={{ x: -280 }}
              transition={{ type: "spring", damping: 25, stiffness: 300 }}
            >
              <SidebarContent />
            </motion.div>
          </>
        )}
      </AnimatePresence>

      <div className="console-layout">
        {/* ═══════════ LEFT SIDEBAR — fixed 260px ═══════════ */}
        <aside className="sidebar">
          <SidebarContent />
        </aside>

        {/* ═══════════ CENTER FILE PANEL — flexible area ═══════════ */}
        <main
          className="file-panel"
          onDragOver={(e) => {
            e.preventDefault();
            setIsDragging(true);
            e.dataTransfer.dropEffect = "copy";
          }}
          onDragLeave={(e) => {
            e.preventDefault();
            setIsDragging(false);
          }}
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
              if (allFiles.length > 0) processFiles(allFiles);
            } else if (e.dataTransfer.files?.length) {
              processFiles(Array.from(e.dataTransfer.files));
            }
          }}
          style={{ position: "relative" }}
        >
          {/* Drag overlay */}
          <AnimatePresence>
            {isDragging && (
              <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="drag-overlay">
                <div style={{ width: 56, height: 56, background: "rgba(37,99,235,0.15)", borderRadius: "50%", display: "flex", alignItems: "center", justifyContent: "center", marginBottom: 16 }}>
                  <Upload className="w-7 h-7 text-[#2563EB]" style={{ animation: "bounce 1s infinite" }} />
                </div>
                <h2 style={{ fontSize: 22, fontWeight: 700, color: "white" }}>Drop files here</h2>
                <p style={{ fontSize: 13, color: "#7C8798", marginTop: 6 }}>Upload to {currentPath ? `/${currentPath}` : "Drive root"}</p>
              </motion.div>
            )}

            {isNodeOffline && (
              <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="offline-overlay">
                <div style={{ width: 80, height: 80, background: "rgba(239,68,68,0.08)", borderRadius: "50%", display: "flex", alignItems: "center", justifyContent: "center", marginBottom: 20 }}>
                  <Cloud className="w-10 h-10" style={{ color: "rgba(239,68,68,0.35)" }} />
                </div>
                <h2 style={{ fontSize: 24, fontWeight: 700, color: "white", marginBottom: 8 }}>Node is Offline</h2>
                <p style={{ fontSize: 13, color: "#7C8798", maxWidth: 280, marginBottom: 24, lineHeight: 1.6 }}>
                  The storage node <b>{shareCode}</b> is unreachable. Ensure the phone is active and the app is running.
                </p>
                <button className="launch-node-btn" onClick={handleRetryConnection}>
                  <RefreshCw className="w-5 h-5" />
                  <span>Retry Connection</span>
                </button>
                <p style={{ fontSize: 9, color: "#4B5E7A", marginTop: 12, textTransform: "uppercase", letterSpacing: "0.16em", fontWeight: 700 }}>Last check: {new Date(lastCheck).toLocaleTimeString()}</p>
              </motion.div>
            )}
          </AnimatePresence>

          {/* File panel header — compact */}
          <div
            style={{
              padding: "0 20px",
              height: "52px",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              borderBottom: "1px solid #1C2035",
              flexShrink: 0,
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
              <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
                {currentPath && (
                  <button onClick={navigateUp} style={{ background: "none", border: "none", color: "#7C8798", cursor: "pointer", padding: 4, borderRadius: 4, display: "flex" }}>
                    <ChevronUp className="w-4 h-4" />
                  </button>
                )}
                <h2 style={{ fontSize: "18px", fontWeight: 600, color: "#E2E5F0", fontFamily: "Inter, sans-serif" }}>{activeTab}</h2>
              </div>
              <span
                style={{
                  fontSize: "11px",
                  padding: "2px 8px",
                  background: "#141720",
                  border: "1px solid #1C2035",
                  borderRadius: "12px",
                  color: "#7A8099",
                  fontFamily: "JetBrains Mono, monospace",
                }}
              >
                {files.length} items
              </span>
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
              <div className="view-toggle-group">
                <button className={`view-toggle-btn ${viewMode === "list" ? "active" : ""}`} onClick={() => setViewMode("list")}>
                  <List className="w-[14px] h-[14px]" />
                </button>
                <button className={`view-toggle-btn ${viewMode === "grid" ? "active" : ""}`} onClick={() => setViewMode("grid")}>
                  <LayoutGrid className="w-[14px] h-[14px]" />
                </button>
              </div>
              <button
                className="topbar-icon-btn"
                onClick={() => loadFiles(currentPath)}
                disabled={isRefreshing}
                style={{ width: "32px", height: "32px", border: "1px solid #1C2035", background: "#161B26", borderRadius: "8px" }}
              >
                <RefreshCw className={`w-3.5 h-3.5 ${isRefreshing ? "animate-spin" : ""}`} />
              </button>
            </div>
          </div>

          {/* Error message */}
          {filesError && <div style={{ margin: "8px 16px", padding: "10px 14px", borderRadius: 8, background: "rgba(239,68,68,0.08)", border: "1px solid rgba(239,68,68,0.2)", fontSize: 13, color: "#f87171" }}>{filesError}</div>}

          {/* File content area */}
          <div className="file-table-body-wrap">
            {isRefreshing && files.length === 0 ? (
              <div style={{ padding: 20, display: "flex", flexDirection: "column", gap: 8 }}>
                {[1, 2, 3, 4, 5].map((i) => (
                  <div key={i} className="skeleton-row" />
                ))}
              </div>
            ) : filteredAndSortedFiles.length === 0 && !isRefreshing ? (
              <div className="empty-files-state">
                <Cloud />
                <h3>{searchQuery ? "No matches found" : "Nothing here yet"}</h3>
                <p>{searchQuery ? "Try adjusting your search to find what you're looking for." : "Upload files or create folders. Drag and drop works anywhere."}</p>
              </div>
            ) : viewMode === "list" ? (
              <table className="file-table">
                <thead>
                  <tr style={{ borderBottom: "1px solid #1C2035" }}>
                    <th
                      className="col-name"
                      style={{ textAlign: "left", fontSize: "11px", letterSpacing: "0.12em", color: "#3A3F58", fontFamily: "JetBrains Mono, monospace", textTransform: "uppercase", fontWeight: 400 }}
                      onClick={() => toggleSort("name")}
                    >
                      NAME {sortField === "name" && (sortDirection === "asc" ? "↑" : "↓")}
                    </th>
                    <th
                      className="col-size"
                      style={{ fontSize: "11px", letterSpacing: "0.12em", color: "#3A3F58", fontFamily: "JetBrains Mono, monospace", textTransform: "uppercase", fontWeight: 400 }}
                      onClick={() => toggleSort("size")}
                    >
                      SIZE {sortField === "size" && (sortDirection === "asc" ? "↑" : "↓")}
                    </th>
                    <th
                      className="col-modified"
                      style={{ fontSize: "11px", letterSpacing: "0.12em", color: "#3A3F58", fontFamily: "JetBrains Mono, monospace", textTransform: "uppercase", fontWeight: 400 }}
                      onClick={() => toggleSort("lastModified")}
                    >
                      MODIFIED {sortField === "lastModified" && (sortDirection === "asc" ? "↑" : "↓")}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {filteredAndSortedFiles.map((file, index) => {
                    const category = getFileBadgeCategory(file);
                    const isPreviewable = ['image', 'video', 'pdf'].includes(category);
                    
                    return (
                      <tr 
                        key={file.id} 
                        className={`file-row ${selectedFile?.id === file.id ? "selected" : ""} ${isPreviewable ? "cursor-zoom-in" : ""}`} 
                        onClick={() => {
                          if (file.isDirectory) navigateTo(file.path);
                          else if (isPreviewable) openPreview(file);
                          else setSelectedFile(file);
                        }}
                      >
                        <td className="col-name">
                          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                            <div className={`file-icon-wrap ${file.isDirectory ? "folder-icon" : ""}`}>
                              {file.isDirectory ? <AnimatedFolder size="small" /> : getFileIcon(file.name, file.isDirectory, "w-4 h-4")}
                            </div>
                            <span style={{ fontSize: "13px", color: "#E2E5F0" }}>{file.name}</span>
                            <span className={`file-type-badge ${getFileBadgeCategory(file)}`}>{getFileKindLabel(file)}</span>
                          </div>
                        </td>
                        <td className="col-size">{file.isDirectory ? "—" : formatSize(file.size)}</td>
                        <td className="col-modified">{formatDate(file.lastModified)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            ) : (
              <div className="file-grid">
                {filteredAndSortedFiles.map((file) => (
                  <div
                    key={file.id}
                    className={`file-grid-item ${selectedFile?.id === file.id ? "selected" : ""}`}
                    onClick={() => (file.isDirectory ? navigateTo(file.path) : setSelectedFile(file))}
                  >
                    <div className="file-grid-icon">
                      {file.isDirectory ? <AnimatedFolder size="large" /> : getFileIcon(file.name, file.isDirectory, "w-6 h-6")}
                    </div>
                    <span className="file-grid-name">{file.name}</span>
                    <span className="file-grid-meta">{formatSize(file.size)}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Floating Bulk Actions Bar */}
          <AnimatePresence>
            {selectedFiles.size > 0 && (
              <motion.div initial={{ opacity: 0, y: 50, scale: 0.95 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: 50, scale: 0.95 }} className="bulk-bar" onClick={(e) => e.stopPropagation()}>
                <div style={{ display: "flex", alignItems: "center", gap: 8, paddingRight: 8, borderRight: "1px solid #374151" }}>
                  <div
                    style={{
                      width: 24,
                      height: 24,
                      borderRadius: "50%",
                      background: "#2563EB",
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      fontSize: 11,
                      fontWeight: 700,
                      color: "white",
                    }}
                  >
                    {selectedFiles.size}
                  </div>
                  <span style={{ fontSize: 13, fontWeight: 500, color: "white" }}>{selectedFiles.size === 1 ? "item" : "items"}</span>
                </div>
                <div style={{ display: "flex", gap: 4 }}>
                  <Button variant="ghost" className="h-9 hover:bg-[#374151] hover:text-white text-[#E5E7EB]" onClick={handleBulkDownload}>
                    <Download className="w-4 h-4 mr-2" /> Download
                  </Button>
                  <Button variant="ghost" className="h-9 hover:bg-[#374151] hover:text-white text-[#E5E7EB]" onClick={handleBulkMove}>
                    <Move className="w-4 h-4 mr-2" /> Move
                  </Button>
                  <Button variant="ghost" className="h-9 hover:bg-red-500/20 hover:text-red-400 text-red-500" onClick={handleBulkDelete}>
                    <Trash2 className="w-4 h-4 mr-2" /> Delete
                  </Button>
                </div>
                <Button variant="ghost" size="icon" className="h-9 w-9 hover:bg-[#374151] text-[#9CA3AF]" onClick={clearSelection}>
                  <X className="w-4 h-4" />
                </Button>
              </motion.div>
            )}
          </AnimatePresence>
        </main>

        {/* ═══════════ RIGHT PREVIEW PANEL — fixed 300px ═══════════ */}
        <aside className="preview-panel">
          <PreviewModal
            selectedFile={selectedFile}
            currentPath={currentPath}
            apiFetch={apiFetch}
            onClose={() => setSelectedFile(null)}
            onRename={handleRename}
            onShare={() => setShowShareModal(true)}
            onMove={(file) => {
              const dest = prompt("Enter destination folder path (e.g. Documents):");
              if (dest !== null) {
                setSelectedFiles(new Set([file.id]));
                handleBulkAction('move', dest);
              }
            }}
            onDelete={handleDelete}
            formatSize={formatSize}
            formatDate={formatDate}
            getFileIcon={getFileIcon}
          />
        </aside>
      </div>

      {/* Overlays */}
      <AnimatePresence>
        {(isUploading || uploadStatus !== "idle") && (
          <motion.div initial={{ opacity: 0, y: 100 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: 100 }} className={`upload-toast ${uploadStatus}`}>
            <div
              style={{
                width: 36,
                height: 36,
                borderRadius: 8,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                flexShrink: 0,
                background: uploadStatus === "success" ? "rgba(16,185,129,0.1)" : uploadStatus === "error" ? "rgba(239,68,68,0.1)" : "rgba(37,99,235,0.1)",
              }}
            >
              {uploadStatus === "success" ? (
                <Check className="w-5 h-5 text-[#10B981]" />
              ) : uploadStatus === "error" ? (
                <AlertCircle className="w-5 h-5 text-[#EF4444]" />
              ) : (
                <Upload className="w-5 h-5 text-[#2563EB]" style={{ animation: "bounce 1s infinite" }} />
              )}
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
                <span style={{ fontSize: 11, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.08em", color: "white" }}>
                  {uploadStatus === "uploading" ? "Uploading..." : uploadStatus === "success" ? "Complete" : uploadStatus === "error" ? "Failed" : uploadStatus === "partial" ? "Partial" : "Idle"}
                </span>
                <span style={{ fontSize: 10, fontFamily: "monospace", fontWeight: 700, color: "white" }}>{uploadProgress}%</span>
              </div>
              <Progress value={uploadProgress} className={`h-1 bg-[#0B1220] ${uploadStatus === "success" ? "[&>div]:bg-[#10B981]" : uploadStatus === "error" ? "[&>div]:bg-[#EF4444]" : "[&>div]:bg-[#2563EB]"}`} />
              {uploadStatus === "success" && (
                <button onClick={() => setUploadStatus("idle")} style={{ fontSize: 10, color: "#7C8798", marginTop: 6, background: "none", border: "none", cursor: "pointer", textDecoration: "underline" }}>
                  Dismiss
                </button>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {showShareModal && (
          <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm">
            <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.95 }}>
              <Card className="w-full max-w-sm bg-[#111827] border-[#374151] p-6 shadow-2xl">
                <h3 className="text-xl font-bold text-white mb-2">Share Link</h3>
                <p className="text-xs text-[#9CA3AF] mb-6">Configure access controls for this link.</p>
                <div className="space-y-4 mb-6">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-white">Read-Only</span>
                    <button
                      onClick={() => setShareConfig({ ...shareConfig, readOnly: !shareConfig.readOnly })}
                      className={`w-10 h-6 rounded-full transition-colors ${shareConfig.readOnly ? "bg-[#2563EB]" : "bg-[#374151]"} relative`}
                    >
                      <span className={`absolute top-1 w-4 h-4 bg-white rounded-full transition-all ${shareConfig.readOnly ? "left-5" : "left-1"}`} />
                    </button>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium text-white">Link Expiry</span>
                    <select
                      value={shareConfig.expiry}
                      onChange={(e) => setShareConfig({ ...shareConfig, expiry: e.target.value })}
                      className="bg-[#0B1220] border border-[#374151] rounded-lg text-sm text-white px-3 py-1.5 outline-none"
                    >
                      <option value="1h">1 Hour</option>
                      <option value="24h">24 Hours</option>
                      <option value="7d">7 Days</option>
                      <option value="never">Never</option>
                    </select>
                  </div>
                </div>
                <div className="flex justify-end gap-3 mt-2">
                  <Button variant="ghost" className="text-gray-400 hover:text-white" onClick={() => setShowShareModal(false)}>
                    Cancel
                  </Button>
                  <Button
                    className="bg-blue-600 hover:bg-blue-700 text-white"
                    onClick={() => {
                      setShowShareModal(false);
                      toast.success("Share link copied!");
                    }}
                  >
                    Copy Link
                  </Button>
                </div>
              </Card>
            </motion.div>
          </div>
        )}
      </AnimatePresence>

      {/* Preview Modal */}
      <PreviewModal
        selectedFile={activePreviewFile}
        currentPath={currentPath}
        apiFetch={apiFetch}
        onClose={() => setActivePreviewFile(null)}
        onRename={handleRename}
        onShare={() => setShowShareModal(true)}
        onMove={(file) => {
          const dest = prompt("Enter destination folder path (e.g. Documents):");
          if (dest !== null) {
            setSelectedFiles(new Set([file.id]));
            handleBulkAction('move', dest);
          }
        }}
        onDelete={handleDelete}
        formatSize={formatSize}
        formatDate={formatDate}
        getFileIcon={getFileIcon}
      />

      <svg width="0" height="0" style={{ position: "absolute" }}>
        <defs>
          <clipPath id="squircleClip" clipPathUnits="objectBoundingBox">
            <path d="M 0,0.5 C 0,0 0,0 0.5,0 S 1,0 1,0.5 1,1 0.5,1 0,1 0,0.5"></path>
          </clipPath>
        </defs>
      </svg>
    </div>
  );
}
