/**
 * PreviewManager Utility — Easy Cloud Storage
 * 
 * This utility abstracts the file fetching process across P2P and Relay modes,
 * ensures robust path encoding for nested folders, and correctly types Blobs
 * for native browser previews.
 */

export interface FileNode {
  id: string;
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  lastModified: number;
  mimeType?: string;
}

/**
 * Common file extensions that browsers can preview natively.
 */
export const SUPPORTED_PREVIEW_TYPES = [
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico',
  'mp4', 'webm', 'mov', 'avi', 'mkv',
  'mp3', 'wav', 'ogg',
  'pdf',
  'txt', 'md', 'json', 'log', 'csv',
  'js', 'ts', 'tsx', 'jsx', 'py', 'java', 'kt', 'go', 'rs', 'c', 'cpp', 'h', 'css', 'scss', 'html', 'htm', 'xml'
];

/**
 * Map of file extensions to their corresponding MIME types.
 */
export const MIME_MAP: Record<string, string> = {
  // Images
  'png': 'image/png',
  'jpg': 'image/jpeg',
  'jpeg': 'image/jpeg',
  'gif': 'image/gif',
  'webp': 'image/webp',
  'svg': 'image/svg+xml',
  'bmp': 'image/bmp',
  'ico': 'image/x-icon',
  
  // Videos
  'mp4': 'video/mp4',
  'webm': 'video/webm',
  'mov': 'video/quicktime',
  'avi': 'video/x-msvideo',
  'mkv': 'video/x-matroska',
  
  // Audio
  'mp3': 'audio/mpeg',
  'wav': 'audio/wav',
  'ogg': 'audio/ogg',
  
  // Documents
  'pdf': 'application/pdf',
  'txt': 'text/plain;charset=utf-8',
  'md': 'text/markdown',
  'json': 'application/json',
  'log': 'text/plain;charset=utf-8',
  'csv': 'text/csv',
  
  // Code & Setup
  'js': 'text/javascript',
  'ts': 'text/typescript',
  'tsx': 'text/typescript',
  'jsx': 'text/javascript',
  'py': 'text/plain;charset=utf-8',
  'java': 'text/plain;charset=utf-8',
  'kt': 'text/plain;charset=utf-8',
  'go': 'text/plain;charset=utf-8',
  'rs': 'text/plain;charset=utf-8',
  'c': 'text/plain;charset=utf-8',
  'cpp': 'text/plain;charset=utf-8',
  'h': 'text/plain;charset=utf-8',
  'css': 'text/css',
  'scss': 'text/x-scss',
  'html': 'text/html',
  'htm': 'text/html',
  'xml': 'application/xml'
};

/**
 * Fetches a file and returns a typed Blob for preview.
 * 
 * @param file The file metadata object
 * @param apiFetch The unified fetcher from WebConsole (abstracts P2P vs Relay)
 * @returns A promise resolving to a typed Blob
 */
export const fetchFileBlob = async (
  file: FileNode, 
  apiFetch: (endpoint: string) => Promise<any>
): Promise<Blob> => {
  const ext = file.name.split('.').pop()?.toLowerCase() || '';
  const mimeType = MIME_MAP[ext] || 'application/octet-stream';

  const queryParams = new URLSearchParams({
    path: file.path || file.name,
  });
  
  const endpoint = `/api/file-content?${queryParams.toString()}`;

  try {
    // 2. UNIFIED FETCH
    // apiFetch already differentiates between P2P DataChannel vs HTTP Relay fallback.
    const response = await apiFetch(endpoint);

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: Failed to fetch file content.`);
    }

    // 3. TYPED BLOB CONSTRUCTION
    // We explicitly cast the raw buffer into a Blob with the correct MIME type.
    // This ensures browsers don't reject the preview due to missing/inferred types.
    const rawBlob = await response.blob();
    
    return new Blob([rawBlob], { type: mimeType });
  } catch (error: any) {
    console.error(`[PreviewManager] Fetch failed for ${file.name}:`, error);
    throw error;
  }
};
