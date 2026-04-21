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
  headers?: Record<string, string>;
  displayName?: string;
  size?: number;
  onProgress?: (sentBytes: number) => void;
}

// ── P2P Transport ────────────────────────────────────────────────────────────

export class P2PTransport {
  private dc: RTCDataChannel | null = null;
  private pending = new Map<string, PendingRequest>();
  private _ready = false;

  get ready(): boolean {
    return this._ready && this.dc?.readyState === 'open';
  }

  attach(dataChannel: RTCDataChannel) {
    this.dc = dataChannel;
    dataChannel.binaryType = 'arraybuffer';
    dataChannel.bufferedAmountLowThreshold = 1048576; // 1MB threshold for streaming backpressure

    dataChannel.addEventListener('open', () => {
      this._ready = true;
      console.log('[P2P] DataChannel open — transport ready');
    });

    dataChannel.addEventListener('close', () => {
      this._ready = false;
      console.log('[P2P] DataChannel closed');
      // Reject all pending requests
      this.pending.forEach((req) => req.reject(new Error('DataChannel closed')));
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
   * Send an API request over the DataChannel and return a Response-like object.
   * This is the primary replacement for fetch() in the Web Console.
   */
  async fetch(path: string, options: {
    method?: string;
    headers?: Record<string, string>;
    body?: string | ArrayBuffer | null;
  } = {}): Promise<P2PResponse> {
    if (!this.dc || this.dc.readyState !== 'open') {
      throw new Error('Data channel is not open');
    }

    const id = crypto.randomUUID();
    const method = options.method || 'GET';

    console.log(`[REQ_DEBUG] P2P Fetch: ${method} ${path} [${id}]`);

    // Encode body as base64 if present
    let bodyB64: string | null = null;
    if (options.body) {
      if (typeof options.body === 'string') {
        bodyB64 = btoa(options.body);
      } else {
        bodyB64 = arrayBufferToBase64(options.body);
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

      // Send the JSON request over the DataChannel
      this.dc!.send(JSON.stringify(request));

      // Timeout after 3 minutes
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error('P2P request timed out'));
        }
      }, 180_000);
    });
  }

  /**
   * Upload a file over the DataChannel using chunked binary streaming.
   * The file is read in 64KB slices to prevent memory overflow.
   */
  async upload(path: string, query: string, file: File, headers: Record<string, string> = {}): Promise<P2PResponse> {
    return this.uploadStream(path, query, file.stream(), {
      headers,
      displayName: file.name,
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

    const id = crypto.randomUUID();
    console.log(`[REQ_DEBUG] P2P Upload: ${path} [${id}]`);

    return new Promise<P2PResponse>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });

      const idBytes = new TextEncoder().encode(id.padEnd(36, ' '));

      // 1. Send upload-start message (Type 1)
      const startPayload = JSON.stringify({
        path,
        query,
        headers: options.headers || {},
        contentLength: options.size ?? -1,
        fileName: options.displayName || 'stream.bin',
      });
      const startPayloadBytes = new TextEncoder().encode(startPayload);
      const startPacket = new Uint8Array(36 + 1 + startPayloadBytes.length);
      startPacket.set(idBytes.slice(0, 36), 0);
      startPacket.set([MSG_TYPE_START], 36);
      startPacket.set(startPayloadBytes, 37);
      this.dc!.send(startPacket);

      // 2. Stream the payload in 64KB packets
      const reader = stream.getReader();
      let sentBytes = 0;

      const waitForLowBuffer = (): Promise<void> => {
        return new Promise((resolveWait, rejectWait) => {
          if (!this.dc || this.dc.readyState !== 'open') {
            rejectWait(new Error('Data channel closed'));
            return;
          }
          if (this.dc.bufferedAmount <= this.dc.bufferedAmountLowThreshold) {
            resolveWait();
            return;
          }
          let timer: any;
          const onLow = () => {
            clearTimeout(timer);
            this.dc!.removeEventListener('bufferedamountlow', onLow);
            resolveWait();
          };
          this.dc.addEventListener('bufferedamountlow', onLow);
          timer = setTimeout(() => {
            this.dc!.removeEventListener('bufferedamountlow', onLow);
            rejectWait(new Error('Data channel buffer throttling stalled (timeout)'));
          }, 15000);
        });
      };

      const pumpNextChunk = async () => {
        try {
          await waitForLowBuffer();
        } catch (err) {
          reader.cancel(err).catch(() => {});
          this.pending.delete(id);
          reject(err);
          return;
        }

        const { done, value } = await reader.read();
        if (done) {
          // 3. Send upload-end message (Type 2)
          const endPacket = new Uint8Array(36 + 1);
          endPacket.set(idBytes.slice(0, 36), 0);
          endPacket.set([MSG_TYPE_END], 36);
          this.dc!.send(endPacket);
          console.log(`[REQ_DEBUG] P2P Upload END sent [${id}]. Waiting for ACK...`);
          return;
        }

        const chunkData = value instanceof Uint8Array ? value : new Uint8Array(value);
        if (chunkData.byteLength === 0) {
          await pumpNextChunk();
          return;
        }

        const packet = new Uint8Array(36 + 1 + chunkData.length);
        packet.set(idBytes.slice(0, 36), 0);
        packet.set([MSG_TYPE_DATA], 36);
        packet.set(chunkData, 37);
        this.dc!.send(packet);

        sentBytes += chunkData.length;
        options.onProgress?.(sentBytes);
        await pumpNextChunk();
      };

      pumpNextChunk().catch((error) => {
        reader.cancel(error).catch(() => {});
        this.pending.delete(id);
        reject(error instanceof Error ? error : new Error(String(error)));
      });

      // Timeout
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reader.cancel().catch(() => {});
          reject(new Error('Upload timed out'));
        }
      }, 600_000); // 10 minutes for large uploads
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
    if (data.byteLength < 36) return;

    // First 36 bytes = request ID
    const idBytes = new Uint8Array(data, 0, 36);
    const reqId = new TextDecoder().decode(idBytes).trim();

    const req = this.pending.get(reqId);
    if (!req || !req.chunks) return;

    // Remaining bytes: First byte is TYPE, then payload
    const type = new Uint8Array(data, 36, 1)[0];
    const payload = new Uint8Array(data, 37);

    if (type === MSG_TYPE_DATA) {
      req.chunks.push(payload);
    } else if (type === MSG_TYPE_ACK) {
      this.pending.delete(reqId);
      console.log(`[REQ_DEBUG] P2P Upload ACK received [${reqId}]`);
      const body = payload.length > 0 ? JSON.parse(new TextDecoder().decode(payload)) : { success: true };
      req.resolve(createP2PResponse(200, { 'Content-Type': 'application/json' }, new TextEncoder().encode(JSON.stringify(body))));
    } else if (type === MSG_TYPE_ERROR) {
      this.pending.delete(reqId);
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
