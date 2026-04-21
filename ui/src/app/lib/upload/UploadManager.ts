import { createTarArchiveStream, filesToArchiveEntries } from "./tarStream";

export type UploadStatus = 'idle' | 'uploading' | 'finalizing' | 'extracting' | 'success' | 'error' | 'partial';

export interface UploadBatch {
  id: string;
  files: File[];
  archiveLabel: string;
}

export type ProgressCallback = (sent: number, total: number, status: UploadStatus) => void;

class UploadManager {
  private queue: UploadBatch[] = [];
  private isProcessing = false;
  private currentBatchId: string | null = null;

  async enqueue(files: File[], onProgress: ProgressCallback, uploadArchiveViaBestPath: any) {
    const archiveEntries = filesToArchiveEntries(files);
    const rootNames = new Set(
      archiveEntries
        .map((entry) => entry.path.split('/')[0])
        .filter((segment) => segment && segment.length > 0)
    );
    const archiveLabel = rootNames.size === 1
      ? `${[...rootNames][0]}.tar`
      : `upload-${new Date().getTime()}.tar`;
    
    const batch: UploadBatch = {
      id: crypto.randomUUID(),
      files,
      archiveLabel
    };

    this.queue.push(batch);
    if (!this.isProcessing) {
      await this.processQueue(onProgress, uploadArchiveViaBestPath);
    }
  }

  private async processQueue(onProgress: ProgressCallback, uploadArchiveViaBestPath: any) {
    this.isProcessing = true;

    for (const batch of this.queue) {
      this.currentBatchId = batch.id;
      try {
        await this.uploadBatch(batch, onProgress, uploadArchiveViaBestPath);
      } catch (error) {
        console.error(`Failed to upload batch ${batch.id}:`, error);
        // We continue to next batch or stop based on requirements. 
        // Strict sequence usually means we continue but mark error.
      }
    }

    this.queue = [];
    this.isProcessing = false;
    this.currentBatchId = null;
  }

  private async uploadBatch(batch: UploadBatch, onProgress: ProgressCallback, uploadArchiveViaBestPath: any) {
    const archiveEntries = filesToArchiveEntries(batch.files);
    const uploadId = batch.id;
    
    let lastError: Error | null = null;

    // Retry logic is preserved here if needed, but the loop MUST block.
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const tarArchive = createTarArchiveStream(archiveEntries);
        const totalSize = tarArchive.byteLength;

        // CRITICAL: We AWAIT the full response (including ACK) before returning.
        const result = await uploadArchiveViaBestPath(
          batch.archiveLabel,
          uploadId,
          totalSize,
          () => tarArchive.stream,
          attempt,
          (sent: number) => {
            const status: UploadStatus = sent >= totalSize ? 'extracting' : 'uploading';
            onProgress(sent, totalSize, status);
          }
        );

        if (result.success) {
          onProgress(totalSize, totalSize, 'success');
          return result;
        } else {
          throw new Error(result.error || 'Upload failed');
        }
      } catch (error) {
        lastError = error instanceof Error ? error : new Error(String(error));
        if (attempt === 0) {
          await new Promise((resolve) => setTimeout(resolve, 1200));
        }
      }
    }

    onProgress(0, 0, 'error');
    throw lastError || new Error('Upload failed after retries');
  }
}

export const uploadManager = new UploadManager();
