/**
 * P2P Transport Layer — WebRTC DataChannel API Communication
 * 
 * This module replaces traditional HTTP fetch() calls with a DataChannel-based
 * transport. API requests are serialized as JSON control messages and sent
 * over the peer-to-peer connection. Responses arrive as JSON (small payloads)
 * or chunked binary (large file downloads).
 * 
 * Memory safety:
 * - Downloads: Binary chunks are accumulated into an array and assembled into
 *   a Blob only when the complete response is received. Individual chunks are
 *   released as they're assembled, preventing memory spikes.
 * - Uploads: Files are read in 64KB slices using FileReader, sent sequentially
 *   over the DataChannel, preventing the entire file from being loaded into memory.
 */

const CHUNK_SIZE = 64 * 1024; // 64KB — matches Android peer's chunk size

// Binary Protocol Message Types
const MSG_TYPE_DATA = 0;
const MSG_TYPE_START = 1;
const MSG_TYPE_END = 2;
const MSG_TYPE_ACK = 3;
const MSG_TYPE_ERROR = 4;

// ── Types ────────────────────────────────────────────────────────────────────

interface PendingRequest {
  resolve: (value: P2PResponse) => void;
  reject: (reason: any) => void;
  /** For chunked responses: accumulated binary chunks */
  chunks?: Uint8Array[];
  /** Response metadata received via res-start */
  headers?: Record<string, string>;
  status?: number;
}

export interface P2PResponse {
  ok: boolean;
  status: number;
  headers: Record<string, string>;
  /** Decoded body as ArrayBuffer */
  arrayBuffer(): Promise<ArrayBuffer>;
  /** Decoded body as parsed JSON */
  json(): Promise<any>;
  /** Decoded body as text */
  text(): Promise<string>;
  /** For file downloads — returns a Blob */
  blob(): Promise<Blob>;
}

interface UploadStreamOptions {
  method?: string;
  headers?: Record<string, string>;
  fileName?: string;
  displayName?: string;
  size?: number;
  onProgress?: (sentBytes: number) => void;
}

// ── P2P Transport ────────────────────────────────────────────────────────────

export class P2PTransport {
  private dc: RTCDataChannel | null = null;
  private pending = new Map<string, PendingRequest>();
  private _ready = false;

  public get dataChannel(): RTCDataChannel | null {
    return this.dc;
  }

  get ready(): boolean {
    return this._ready && this.dc?.readyState === 'open';
  }

  attach(dataChannel: RTCDataChannel) {
    this.dc = dataChannel;
    dataChannel.binaryType = 'arraybuffer';
    dataChannel.bufferedAmountLowThreshold = 512 * 1024; // 512KB threshold for streaming backpressure

    dataChannel.addEventListener('open', () => {
      this._ready = true;
      console.log('[P2P] DataChannel open — transport ready');
    });

    dataChannel.addEventListener('close', () => {
      this._ready = false;
      console.log('[P2P] DataChannel closed');
      // Reject all pending requests with a specific error so UI can handle it
      const error = new Error('DataChannel closed');
      this.pending.forEach((req) => req.reject(error));
      this.pending.clear();
    });

    dataChannel.addEventListener('message', (event: MessageEvent) => {
      if (typeof event.data === 'string') {
        this.handleTextMessage(event.data);
      } else if (event.data instanceof ArrayBuffer) {
        this.handleBinaryChunk(event.data);
      }
    });

    if (dataChannel.readyState === 'open') {
      this._ready = true;
    }
  }

  /**
   * Safe sender that respects SCTP backpressure.
   * Pauses if the buffer exceeds 1MB and resumes on 'bufferedamountlow' (512KB).
   */
  private async sendWithBackpressure(data: Uint8Array): Promise<void> {
    if (!this.dc || this.dc.readyState !== 'open') {
      throw new Error('DataChannel closed mid-transfer');
    }

    // 1MB High Watermark
    if (this.dc.bufferedAmount > 1024 * 1024) {
      await new Promise<void>((resolve, reject) => {
        const cleanup = () => {
          this.dc?.removeEventListener('bufferedamountlow', onLow);
          this.dc?.removeEventListener('close', onFail);
          this.dc?.removeEventListener('error', onFail);
        };

        const onLow = () => {
          cleanup();
          resolve();
        };

        const onFail = (e: any) => {
          cleanup();
          reject(new Error('DataChannel closed or errored during backpressure wait'));
        };

        this.dc?.addEventListener('bufferedamountlow', onLow);
        this.dc?.addEventListener('close', onFail);
        this.dc?.addEventListener('error', onFail);

        // Fail-safe timeout to prevent indefinite hanging
        setTimeout(() => {
          cleanup();
          resolve(); 
        }, 15000);
      });
    }

    if (this.dc.readyState === 'open') {
      this.dc.send(data);
    } else {
      throw new Error('DataChannel closed before send');
    }
  }

  /**
   * Send an API request over the DataChannel and return a Response-like object.
   * This is the primary replacement for fetch() in the Web Console.
   */
  async fetch(path: string, options: {
    method?: string;
    headers?: Record<string, string>;
    body?: string | ArrayBuffer | Blob | ReadableStream | null;
  } = {}): Promise<P2PResponse> {
    if (!this.dc || this.dc.readyState !== 'open') {
      throw new Error('Data channel is not open');
    }

    // Determine if we should use the binary streaming protocol for the request body.
    // We use it if the body is a Blob, Stream, or an ArrayBuffer/String larger than 16KB.
    const isLarge = (body: any) => {
      if (!body) return false;
      if (body instanceof Blob || body instanceof ReadableStream) return true;
      if (typeof body === 'string') return body.length > 16384;
      if (body instanceof ArrayBuffer) return body.byteLength > 16384;
      return false;
    };

    if (isLarge(options.body)) {
      const [pathPart, queryPart] = path.split('?');
      let bodyStream: ReadableStream<Uint8Array>;
      let bodySize = -1;

      if (options.body instanceof ReadableStream) {
        bodyStream = options.body;
      } else if (options.body instanceof Blob) {
        bodyStream = options.body.stream();
        bodySize = options.body.size;
      } else {
        const bytes = typeof options.body === 'string' 
          ? new TextEncoder().encode(options.body) 
          : new Uint8Array(options.body as ArrayBuffer);
        bodySize = bytes.length;
        bodyStream = new ReadableStream({
          start(controller) {
            controller.enqueue(bytes);
            controller.close();
          }
        });
      }

      return this.uploadStream(pathPart, queryPart || '', bodyStream, {
        method: options.method || 'POST',
        headers: options.headers,
        size: bodySize,
      });
    }

    const id = crypto.randomUUID();
    const method = options.method || 'GET';

    console.log(`[REQ_DEBUG] P2P Fetch: ${method} ${path} [${id}]`);

    // Encode body as base64 if present (safe for small payloads)
    let bodyB64: string | null = null;
    if (options.body) {
      if (typeof options.body === 'string') {
        bodyB64 = btoa(options.body);
      } else {
        bodyB64 = arrayBufferToBase64(options.body as ArrayBuffer);
      }
    }

    // Parse path and query
    const [pathPart, queryPart] = path.split('?');

    const request = {
      type: 'req',
      id,
      method,
      path: pathPart,
      query: queryPart || '',
      headers: options.headers || {},
      body: bodyB64,
    };

    return new Promise<P2PResponse>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });

      try {
        // Send the JSON request over the DataChannel
        this.dc!.send(JSON.stringify(request));
      } catch (e) {
        this.pending.delete(id);
        reject(e);
        return;
      }

      // Timeout after 3 minutes for regular API calls
      const timeout = setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`P2P request timed out: ${method} ${path}`));
        }
      }, 180_000);

      // Link request to channel state
      const checkClose = () => {
        if (this.dc?.readyState !== 'open') {
          clearTimeout(timeout);
          this.pending.delete(id);
          reject(new Error('DataChannel closed during request'));
        }
      };
      this.dc?.addEventListener('close', checkClose, { once: true });
    });
  }

  /**
   * Upload a file over the DataChannel using chunked binary streaming.
   * The file is read in 64KB slices to prevent memory overflow.
   */
  async upload(path: string, query: string, file: File, headers: Record<string, string> = {}): Promise<P2PResponse> {
    return this.uploadStream(path, query, file.stream(), {
      headers,
      fileName: file.name,
      size: file.size,
    });
  }

  /**
   * Stream an arbitrary ReadableStream over the DataChannel without buffering
   * the whole payload in memory. The sender throttles on bufferedAmount so a
   * slow receiver naturally pauses the archive producer.
   */
  async uploadStream(
    path: string,
    query: string,
    stream: ReadableStream<Uint8Array>,
    options: UploadStreamOptions = {},
  ): Promise<P2PResponse> {
    if (!this.dc || this.dc.readyState !== 'open') {
      throw new Error('Data channel is not open');
    }

    const requestId = crypto.getRandomValues(new Uint8Array(16));
    const idKey = Array.from(requestId).map(b => b.toString(16).padStart(2, '0')).join('');
    const method = options.method || 'POST';

    console.log(`[REQ_DEBUG] P2P Binary Stream: ${method} ${path} [${idKey}]`);

    return new Promise<P2PResponse>((resolve, reject) => {
      this.pending.set(idKey, { resolve, reject });

      const startPayload = JSON.stringify({
        method,
        path,
        query,
        headers: options.headers || {},
        contentLength: options.size ?? -1,
        fileName: options.fileName || options.displayName || 'upload.tar'
      });
      const startPayloadBytes = new TextEncoder().encode(startPayload);
      let seqNum = 0;

      const uploadProcess = async () => {
        try {
          // 1. Send START packet
          const startPacket = new Uint8Array(16 + 1 + 4 + startPayloadBytes.length);
          startPacket.set(requestId, 0);
          startPacket.set([MSG_TYPE_START], 16);
          new DataView(startPacket.buffer).setUint32(17, seqNum++, false);
          startPacket.set(startPayloadBytes, 21);
          await this.sendWithBackpressure(startPacket);

          const reader = stream.getReader();
          let sentBytes = 0;

          try {
            while (true) {
              const { done, value } = await reader.read();
              if (done) break;

              // 2. Slice and send DATA chunks
              const data = value instanceof Uint8Array ? value : new Uint8Array(value);
              let offset = 0;
              
              while (offset < data.length) {
                const chunkLen = Math.min(CHUNK_SIZE, data.length - offset);
                const chunkSlice = data.slice(offset, offset + chunkLen);
                
                const packet = new Uint8Array(16 + 1 + 4 + chunkSlice.length);
                packet.set(requestId, 0);
                packet.set([MSG_TYPE_DATA], 16);
                new DataView(packet.buffer).setUint32(17, seqNum++, false);
                packet.set(chunkSlice, 21);

                await this.sendWithBackpressure(packet);
                
                offset += chunkLen;
                sentBytes += chunkLen;
                options.onProgress?.(sentBytes);
              }
            }

            // 3. Send END packet
            const endPacket = new Uint8Array(16 + 1 + 4);
            endPacket.set(requestId, 0);
            endPacket.set([MSG_TYPE_END], 16);
            new DataView(endPacket.buffer).setUint32(17, seqNum++, false);
            await this.sendWithBackpressure(endPacket);
            
            console.log(`[REQ_DEBUG] P2P Upload END sent [${idKey}]. Waiting for ACK...`);

          } finally {
            reader.releaseLock();
          }

        } catch (error) {
          console.error(`[P2P] Upload failed [${idKey}]:`, error);
          this.pending.delete(idKey);
          reject(error instanceof Error ? error : new Error(String(error)));
        }
      };

      uploadProcess();

      // Long timeout for large uploads
      setTimeout(() => {
        if (this.pending.has(idKey)) {
          this.pending.delete(idKey);
          reject(new Error('Upload timed out after 10 minutes'));
        }
      }, 600_000);
    });
  }

  // ── Internal message handlers ──────────────────────────────────────────

  private handleTextMessage(text: string) {
    try {
      const msg = JSON.parse(text);
      const { type, id } = msg;

      switch (type) {
        case 'res': {
          // Single-message JSON response (small payloads)
          const req = this.pending.get(id);
          if (!req) return;
          this.pending.delete(id);

          console.log(`[REQ_DEBUG] P2P Response [${id}]: ${msg.status}`);

          const bodyBytes = msg.body ? base64ToUint8Array(msg.body) : new Uint8Array(0);
          req.resolve(createP2PResponse(msg.status || 200, msg.headers || {}, bodyBytes));
          break;
        }

        case 'res-start': {
          // Start of a chunked binary response (large file download)
          const req = this.pending.get(id);
          if (!req) return;
          req.chunks = [];
          req.headers = msg.headers || {};
          req.status = msg.status || 200;
          break;
        }

        case 'res-end': {
          // End of chunked response — assemble chunks into final response
          const req = this.pending.get(id);
          if (!req || !req.chunks) return;
          this.pending.delete(id);

          console.log(`[REQ_DEBUG] P2P Chunked Response Assembled [${id}]: ${req.status} (Chunks: ${req.chunks.length})`);

          // Concatenate all chunks into a single Uint8Array
          const totalLength = req.chunks.reduce((acc, c) => acc + c.length, 0);
          const assembled = new Uint8Array(totalLength);
          let writeOffset = 0;
          for (const chunk of req.chunks) {
            assembled.set(chunk, writeOffset);
            writeOffset += chunk.length;
          }
          req.chunks = []; // Release chunk references

          req.resolve(createP2PResponse(req.status || 200, req.headers || {}, assembled));
          break;
        }
      }
    } catch (e) {
      console.error('[P2P] Failed to parse text message:', e);
    }
  }

  private handleBinaryChunk(data: ArrayBuffer) {
    if (data.byteLength < 21) return;

    const idBytes = new Uint8Array(data, 0, 16);
    const idKey = Array.from(idBytes).map(b => b.toString(16).padStart(2, '0')).join('');

    const req = this.pending.get(idKey);
    if (!req) return;

    const type = new Uint8Array(data, 16, 1)[0];
    const payload = new Uint8Array(data, 21);

    if (type === MSG_TYPE_DATA) {
      if (req.chunks) req.chunks.push(payload);
    } else if (type === MSG_TYPE_ACK) {
      this.pending.delete(idKey);
      console.log(`[REQ_DEBUG] P2P Upload ACK received [${idKey}]`);
      const body = payload.length > 0 ? JSON.parse(new TextDecoder().decode(payload)) : { success: true };
      req.resolve(createP2PResponse(200, { 'Content-Type': 'application/json' }, new TextEncoder().encode(JSON.stringify(body))));
    } else if (type === MSG_TYPE_ERROR) {
      this.pending.delete(idKey);
      const errorText = new TextDecoder().decode(payload);
      req.reject(new Error(errorText || 'Upload failed on backend'));
    }
  }

  destroy() {
    this.pending.forEach((req) => req.reject(new Error('Transport destroyed')));
    this.pending.clear();
    this.dc?.close();
    this.dc = null;
    this._ready = false;
  }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

function createP2PResponse(
  status: number,
  headers: Record<string, string>,
  body: Uint8Array
): P2PResponse {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers,
    async arrayBuffer() { return body.buffer as ArrayBuffer; },
    async json() { return JSON.parse(new TextDecoder().decode(body)); },
    async text() { return new TextDecoder().decode(body); },
    async blob() {
      const contentType = Object.entries(headers).find(
        ([k]) => k.toLowerCase() === 'content-type'
      )?.[1] || 'application/octet-stream';
      return new Blob([body.buffer as ArrayBuffer], { type: contentType });
    },
  };
}

function arrayBufferToBase64(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

function base64ToUint8Array(b64: string): Uint8Array {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}
