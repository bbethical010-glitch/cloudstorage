import type { P2PResponse } from '../../hooks/p2pTransport';

/**
 * RelayTransport — Fast binary transport over WebSocket relay.
 * 
 * Used as a fallback when WebRTC P2P is unavailable. Replaces legacy JSON+Base64
 * logic with a high-performance binary frame protocol: [16 bytes UUID][1 byte TYPE][4 byte Seq][binary data].
 */

const MSG_TYPE_DATA = 0;
const MSG_TYPE_START = 1;
const MSG_TYPE_END = 2;
const MSG_TYPE_ACK = 3;
const MSG_TYPE_ERROR = 4;

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
    
    // Convert body if present
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
      fileName?: string;
      onProgress?: (sent: number) => void;
    } = {}
  ): Promise<P2PResponse> {
    if (!this.ready) throw new Error('Relay WebSocket is not connected');

    const requestId = crypto.getRandomValues(new Uint8Array(16));
    const idKey = Array.from(requestId).map(b => b.toString(16).padStart(2, '0')).join('');

    return new Promise((resolve, reject) => {
      this.pending.set(idKey, { resolve, reject });

      // 1. Signaling start (Type 1)
      const startPayload = JSON.stringify({
        method: 'POST',
        path,
        query,
        headers: options.headers || {},
        contentLength: options.size ?? -1,
        fileName: options.fileName || 'upload.tar'
      });
      const startPayloadBytes = new TextEncoder().encode(startPayload);
      const startPacket = new Uint8Array(16 + 1 + 4 + startPayloadBytes.length);
      startPacket.set(requestId, 0);
      startPacket.set([MSG_TYPE_START], 16);
      new DataView(startPacket.buffer).setUint32(17, 0, false);
      startPacket.set(startPayloadBytes, 21);
      
      this.ws!.send(startPacket);

      // 2. Pump binary frames
      const reader = stream.getReader();
      let sentBytes = 0;
      let seq = 1;

      const pump = async () => {
        // Backpressure check (WebSocket bufferedAmount)
        if (this.ws!.bufferedAmount > 256 * 1024) {
          await new Promise(r => setTimeout(r, 50));
          return pump();
        }

        const { done, value } = await reader.read();
        if (done) {
          const endPacket = new Uint8Array(16 + 1 + 4);
          endPacket.set(requestId, 0);
          endPacket.set([MSG_TYPE_END], 16);
          new DataView(endPacket.buffer).setUint32(17, seq++, false);
          this.ws!.send(endPacket);
          return;
        }

        const chunk = value instanceof Uint8Array ? value : new Uint8Array(value);
        const packet = new Uint8Array(16 + 1 + 4 + chunk.length);
        packet.set(requestId, 0);
        packet.set([MSG_TYPE_DATA], 16);
        new DataView(packet.buffer).setUint32(17, seq++, false);
        packet.set(chunk, 21);

        this.ws!.send(packet);
        sentBytes += chunk.length;
        options.onProgress?.(sentBytes);
        
        return pump();
      };

      pump().catch(err => {
        reader.cancel().catch(() => {});
        this.pending.delete(idKey);
        reject(err);
      });
    });
  }

  /**
   * Inbound message handler
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
      const buffer = data as ArrayBuffer;
      if (buffer.byteLength < 21) return;

      const idBytes = new Uint8Array(buffer, 0, 16);
      const requestId = Array.from(idBytes).map(b => b.toString(16).padStart(2, '0')).join('');
      const type = new Uint8Array(buffer, 16, 1)[0];
      const payload = new Uint8Array(buffer, 21);

      const req = this.pending.get(requestId);
      if (!req) return;

      if (type === MSG_TYPE_ACK) {
        this.pending.delete(requestId);
        const body = payload.length > 0 ? JSON.parse(new TextDecoder().decode(payload)) : { success: true };
        req.resolve(this.createResponse(200, { 'Content-Type': 'application/json' }, new TextEncoder().encode(JSON.stringify(body))));
      } else if (type === MSG_TYPE_ERROR) {
        this.pending.delete(requestId);
        const errorText = new TextDecoder().decode(payload);
        req.reject(new Error(errorText || 'Relay streaming failed'));
      }
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
