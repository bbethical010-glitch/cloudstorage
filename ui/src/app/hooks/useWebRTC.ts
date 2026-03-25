/**
 * useWebRTC — React hook for WebRTC peer-to-peer connections
 * 
 * This hook manages the entire WebRTC lifecycle:
 * 1. Opens a signaling WebSocket to the relay server
 * 2. Creates an RTCPeerConnection with STUN servers for NAT traversal
 * 3. Creates an RTCDataChannel for API communication
 * 4. Exchanges SDP offer/answer and ICE candidates via the relay
 * 5. Exposes a P2PTransport instance for making API requests
 *
 * NAT Traversal (STUN):
 * The browser and Android device are typically behind NAT routers, which
 * means they don't know their own public IP/port. STUN servers help both
 * sides discover their public-facing network address. Once both peers know
 * each other's public address, they can establish a direct UDP connection
 * that bypasses the relay entirely.
 */

import { useEffect, useRef, useState, useCallback } from 'react';
import { P2PTransport } from './p2pTransport';

export type P2PConnectionState = 'disconnected' | 'connecting' | 'signaling' | 'connected' | 'failed';

// Public STUN servers for NAT traversal — these help discover
// the device's public IP/port mapping behind home/office routers
const ICE_SERVERS: RTCIceServer[] = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
];

interface UseWebRTCOptions {
  /** The relay server URL (e.g., https://easy-storage-relay.onrender.com) */
  relayUrl: string;
  /** The Android node's share code */
  shareCode: string;
  /** Whether to auto-connect */
  enabled?: boolean;
}

interface UseWebRTCReturn {
  /** Current connection state */
  connectionState: P2PConnectionState;
  /** The P2P transport for making API requests */
  transport: P2PTransport | null;
  /** Whether the transport is ready for API calls */
  isReady: boolean;
  /** Reconnect if disconnected */
  reconnect: () => void;
}

export function useWebRTC({ relayUrl, shareCode, enabled = true }: UseWebRTCOptions): UseWebRTCReturn {
  const [connectionState, setConnectionState] = useState<P2PConnectionState>('disconnected');
  const [isReady, setIsReady] = useState(false);
  const transportRef = useRef<P2PTransport>(new P2PTransport());
  const pcRef = useRef<RTCPeerConnection | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const dcRef = useRef<RTCDataChannel | null>(null);
  const reconnectRef = useRef<number>(0);

  const connect = useCallback(() => {
    if (!enabled || !relayUrl || !shareCode) return;

    // Cleanup previous connection
    cleanup();

    setConnectionState('connecting');

    // 1. Build signaling WebSocket URL
    const wsUrl = relayUrl
      .replace('https://', 'wss://')
      .replace('http://', 'ws://') + `/signal/${shareCode.toUpperCase()}`;

    console.log('[WebRTC] Connecting to signaling server:', wsUrl);
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      console.log('[WebRTC] Signaling WebSocket connected');
      setConnectionState('signaling');
      initiatePeerConnection(ws);
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        handleSignalingMessage(msg);
      } catch (e) {
        console.error('[WebRTC] Failed to parse signaling message:', e);
      }
    };

    ws.onerror = (err) => {
      console.error('[WebRTC] Signaling WebSocket error:', err);
      setConnectionState('failed');
    };

    ws.onclose = () => {
      console.log('[WebRTC] Signaling WebSocket closed');
      if (connectionState !== 'connected') {
        setConnectionState('disconnected');
      }
      // Auto-reconnect with exponential backoff
      const delay = Math.min(2000 * Math.pow(2, reconnectRef.current), 30000);
      reconnectRef.current++;
      setTimeout(() => {
        if (enabled) connect();
      }, delay);
    };
  }, [relayUrl, shareCode, enabled]);

  function initiatePeerConnection(ws: WebSocket) {
    // 2. Create RTCPeerConnection with STUN servers
    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    pcRef.current = pc;

    // 3. Create a DataChannel for API communication
    const dc = pc.createDataChannel('files', {
      ordered: true, // Maintain message order for API request/response matching
    });
    dcRef.current = dc;

    // Attach the DataChannel to the P2P transport layer
    transportRef.current.attach(dc);

    dc.onopen = () => {
      console.log('[WebRTC] DataChannel "files" opened — P2P ready!');
      setConnectionState('connected');
      setIsReady(true);
      reconnectRef.current = 0; // Reset backoff on successful connection
    };

    dc.onclose = () => {
      console.log('[WebRTC] DataChannel closed');
      setIsReady(false);
    };

    // 4. Gather ICE candidates and send them to the Android node via relay
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        const signal = {
          type: 'signal',
          signal: {
            type: 'ice',
            candidate: event.candidate.candidate,
            sdpMid: event.candidate.sdpMid,
            sdpMLineIndex: event.candidate.sdpMLineIndex,
          },
        };
        ws.send(JSON.stringify(signal));
      }
    };

    pc.onconnectionstatechange = () => {
      console.log('[WebRTC] Connection state:', pc.connectionState);
      if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        setConnectionState('failed');
        setIsReady(false);
      }
    };

    pc.oniceconnectionstatechange = () => {
      console.log('[WebRTC] ICE connection state:', pc.iceConnectionState);
    };

    // 5. Create the SDP offer and send it to the Android node
    pc.createOffer().then((offer) => {
      return pc.setLocalDescription(offer).then(() => {
        const signal = {
          type: 'signal',
          signal: {
            type: 'offer',
            sdp: offer.sdp,
          },
        };
        ws.send(JSON.stringify(signal));
        console.log('[WebRTC] SDP offer sent to Android node');
      });
    }).catch((err) => {
      console.error('[WebRTC] Failed to create offer:', err);
      setConnectionState('failed');
    });
  }

  function handleSignalingMessage(msg: any) {
    const pc = pcRef.current;
    if (!pc) return;

    if (msg.type === 'status') {
      console.log('[WebRTC] Node status:', msg.agentOnline ? 'online' : 'offline');
      if (!msg.agentOnline) {
        setConnectionState('disconnected');
      }
      return;
    }

    if (msg.type === 'signal' && msg.signal) {
      const signal = msg.signal;

      if (signal.type === 'answer') {
        // Set the Android node's SDP answer as remote description
        const answer = new RTCSessionDescription({
          type: 'answer',
          sdp: signal.sdp,
        });
        pc.setRemoteDescription(answer).then(() => {
          console.log('[WebRTC] Remote description (answer) set successfully');
        }).catch((err) => {
          console.error('[WebRTC] Failed to set remote description:', err);
        });
      } else if (signal.type === 'ice') {
        // Add the Android node's ICE candidate for NAT traversal
        const candidate = new RTCIceCandidate({
          candidate: signal.candidate,
          sdpMid: signal.sdpMid,
          sdpMLineIndex: signal.sdpMLineIndex,
        });
        pc.addIceCandidate(candidate).catch((err) => {
          console.error('[WebRTC] Failed to add ICE candidate:', err);
        });
      }
    }
  }

  function cleanup() {
    dcRef.current?.close();
    pcRef.current?.close();
    wsRef.current?.close();
    transportRef.current.destroy();
    transportRef.current = new P2PTransport();
    dcRef.current = null;
    pcRef.current = null;
    wsRef.current = null;
    setIsReady(false);
  }

  useEffect(() => {
    if (enabled) connect();
    return () => cleanup();
  }, [relayUrl, shareCode, enabled]);

  const reconnect = useCallback(() => {
    reconnectRef.current = 0;
    connect();
  }, [connect]);

  return {
    connectionState,
    transport: transportRef.current,
    isReady,
    reconnect,
  };
}
