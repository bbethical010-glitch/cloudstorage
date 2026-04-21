import type { P2PResponse } from '../../hooks/p2pTransport';

/**
 * RelayTransport — Fast binary transport over WebSocket relay.
 * 
 * Used as a fallback when WebRTC P2P is unavailable. Replaces legacy JSON+Base64
 * logic with a high-performance binary frame protocol: [36 bytes UUID][binary data].
 */
export class RelayTransport {
  private ws: WebSocket | null = null;
  private pending = new Map<string, {
    resolve: (value: P2PResponse) => void;
    reject: (reason: any) => void;
    chunks?: Uint8Array[];
    headers?: Record<string, string>;
    status?: number;
  }>();

  constructor(ws?: WebSocket) {
    if (ws) this.attach(ws);
  }

  attach(ws: WebSocket) {
    this.ws = ws;
    // Set binaryType to 'arraybuffer' for standardized payload handling
    ws.binaryType = 'arraybuffer';
  }

  get ready(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  /**
   * Primary entry point for making requests over the relay.
   */
  async fetch(url: string, options: RequestInit = {}): Promise<P2PResponse> {
    if (!this.ready) throw new Error('Relay WebSocket is not connected');

    const id = crypto.randomUUID();
    const [path, query] = url.split('?');
    
    // Convert body if present (only supports strings for regular requests)
    let bodyBase64: string | null = null;
    if (options.body) {
      if (typeof options.body === 'string') {
        bodyBase64 = btoa(options.body);
      }
    }

    const envelope = {
      type: 'request',
      requestId: id,
      method: options.method || 'GET',
      path: path,
      query: query || '',
      headers: (options.headers as any) || {},
      bodyBase64
    };

    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws!.send(JSON.stringify(envelope));

      // 60s timeout for regular requests
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error('Relay request timed out'));
        }
      }, 60000);
    });
  }

  /**
   * streaming upload over relay using binary frames.
   */
  async uploadStream(
    path: string,
    query: string,
    stream: ReadableStream<Uint8Array>,
    options: {
      headers?: Record<string, string>;
      size?: number;
      onProgress?: (sent: number) => void;
    } = {}
  ): Promise<P2PResponse> {
    if (!this.ready) throw new Error('Relay WebSocket is not connected');

    const id = crypto.randomUUID();
    const requestIdBytes = new TextEncoder().encode(id.padEnd(36, ' '));
    
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });

      // 1. Signaling start
      this.ws!.send(JSON.stringify({
        type: 'stream-request-start',
        requestId: id,
        method: 'POST',
        path,
        query,
        headers: options.headers || {},
        contentLength: options.size ?? -1
      }));

      // 2. Pump binary frames
      const reader = stream.getReader();
      let sentBytes = 0;

      const pump = async () => {
        // Backpressure check (WebSocket bufferedAmount)
        // 256KB threshold to keep the pipe full but responsive
        if (this.ws!.bufferedAmount > 256 * 1024) {
          await new Promise(r => setTimeout(r, 50));
          return pump();
        }

        const { done, value } = await reader.read();
        if (done) {
          this.ws!.send(JSON.stringify({ type: 'stream-request-end', requestId: id }));
          return;
        }

        const chunk = value instanceof Uint8Array ? value : new Uint8Array(value);
        const packet = new Uint8Array(36 + chunk.length);
        packet.set(requestIdBytes.slice(0, 36), 0);
        packet.set(chunk, 36);

        this.ws!.send(packet);
        sentBytes += chunk.length;
        options.onProgress?.(sentBytes);
        
        // Immediate next pump attempt
        return pump();
      };

      pump().catch(err => {
        reader.cancel().catch(() => {});
        this.pending.delete(id);
        reject(err);
      });
    });
  }

  /**
   * Inbound message handler called by useWebRTC
   */
  handleMessage(data: string | ArrayBuffer) {
    if (typeof data === 'string') {
      try {
        const msg = JSON.parse(data);
        if (msg.type === 'response' || msg.type === 'stream-response') {
          const req = this.pending.get(msg.requestId);
          if (!req) return;
          this.pending.delete(msg.requestId);

          const body = msg.bodyBase64 ? Uint8Array.from(atob(msg.bodyBase64), c => c.charCodeAt(0)) : new Uint8Array(0);
          req.resolve(this.createResponse(msg.status || 200, msg.headers || {}, body));
        }
      } catch (e) {
        console.warn('[Relay] Failed to handle text message', e);
      }
    } else {
      // Binary implementation if the relay server sends binary responses back
      // Currently the relay server wraps responses in JSON, but we can optimize this later
    }
  }

  private createResponse(status: number, headers: Record<string, string>, body: Uint8Array): P2PResponse {
    return {
      ok: status >= 200 && status < 300,
      status,
      headers,
      async arrayBuffer() { return body.buffer as ArrayBuffer; },
      async json() { return JSON.parse(new TextDecoder().decode(body)); },
      async text() { return new TextDecoder().decode(body); },
      async blob() { return new Blob([body]); }
    };
  }
}
