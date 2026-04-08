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
}

/**
 * Common file extensions that browsers can preview natively.
 */
export const SUPPORTED_PREVIEW_TYPES = [
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'svg',
  'mp4', 'webm', 'mov', 'avi',
  'pdf',
  'txt', 'md', 'json', 'log'
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
  'txt': 'text/plain',
  'md': 'text/markdown',
  'json': 'application/json',
  'log': 'text/plain',
  'csv': 'text/csv'
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

  // 1. SAFE PATH ENCODING
  // We MUST encode both the path (directory) and the file (name) separately
  // to prevent nested slashes from breaking the Ktor routing logic.
  const queryParams = new URLSearchParams({
    path: file.path, // The directory path (e.g., "Documents/Projects")
    file: file.name  // The file name (e.g., "design.png")
  });
  
  const endpoint = `/api/download?${queryParams.toString()}`;

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
