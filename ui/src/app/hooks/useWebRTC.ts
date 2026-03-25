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
  
  // Queue for ICE candidates received before the remote description is fully set
  const iceCandidateQueueRef = useRef<RTCIceCandidateInit[]>([]);

  const connect = useCallback(() => {
    if (!enabled || !relayUrl || !shareCode) return;

    // Cleanup previous connection stringently
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
      if (wsRef.current === ws) {
        if (connectionState !== 'connected') {
          setConnectionState('disconnected');
        }
        // Auto-reconnect with exponential backoff
        const delay = Math.min(2000 * Math.pow(2, reconnectRef.current), 30000);
        reconnectRef.current++;
        setTimeout(() => {
          if (enabled) connect();
        }, delay);
      }
    };
  }, [relayUrl, shareCode, enabled]);

  function initiatePeerConnection(ws: WebSocket) {
    // 2. Create RTCPeerConnection with STUN servers
    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    pcRef.current = pc;
    iceCandidateQueueRef.current = [];

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
      if (event.candidate && wsRef.current?.readyState === WebSocket.OPEN) {
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
        if (wsRef.current?.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(signal));
          console.log('[WebRTC] SDP offer sent to Android node');
        }
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
        // STATE GUARD: Prevent DOMException when receiving duplicate answers or in wrong state
        if (pc.signalingState !== 'have-local-offer') {
          console.warn('[WebRTC] Ignoring unexpected answer in state:', pc.signalingState);
          return;
        }

        // Set the Android node's SDP answer as remote description
        const answer = new RTCSessionDescription({
          type: 'answer',
          sdp: signal.sdp,
        });
        
        pc.setRemoteDescription(answer).then(() => {
          console.log('[WebRTC] Remote description (answer) set successfully');
          // Process any queued ICE candidates that arrived before the answer
          iceCandidateQueueRef.current.forEach(c => {
            pc.addIceCandidate(new RTCIceCandidate(c)).catch(err => {
              console.warn('[WebRTC] Failed to add queued ICE candidate:', err);
            });
          });
          iceCandidateQueueRef.current = [];
        }).catch((err) => {
          console.error('[WebRTC] Failed to set remote description:', err);
        });

      } else if (signal.type === 'ice') {
        const candidateInit = {
          candidate: signal.candidate,
          sdpMid: signal.sdpMid,
          sdpMLineIndex: signal.sdpMLineIndex,
        };

        // ICE QUEUING: If remote description isn't set yet, queue the candidate
        if (!pc.remoteDescription) {
          console.log('[WebRTC] Queuing ICE candidate (remote description not set)');
          iceCandidateQueueRef.current.push(candidateInit);
          return;
        }

        // Add the Android node's ICE candidate for NAT traversal
        try {
          const candidate = new RTCIceCandidate(candidateInit);
          pc.addIceCandidate(candidate).catch((err) => {
            console.warn('[WebRTC] Failed to add ICE candidate:', err);
          });
        } catch (err) {
          console.warn('[WebRTC] Synchronous error adding ICE candidate:', err);
        }
      }
    }
  }

  function cleanup() {
    console.log('[WebRTC] Cleaning up connection resources');
    
    // Clean up DataChannel
    if (dcRef.current) {
      dcRef.current.onopen = null;
      dcRef.current.onclose = null;
      dcRef.current.close();
      dcRef.current = null;
    }
    
    // Clean up PeerConnection
    if (pcRef.current) {
      pcRef.current.onicecandidate = null;
      pcRef.current.onconnectionstatechange = null;
      pcRef.current.oniceconnectionstatechange = null;
      pcRef.current.close();
      pcRef.current = null;
    }
    
    // Clean up WebSocket
    if (wsRef.current) {
      wsRef.current.onopen = null;
      wsRef.current.onmessage = null;
      wsRef.current.onerror = null;
      wsRef.current.onclose = null;
      wsRef.current.close();
      wsRef.current = null;
    }

    transportRef.current.destroy();
    transportRef.current = new P2PTransport();
    iceCandidateQueueRef.current = [];
    setIsReady(false);
  }

  useEffect(() => {
    if (enabled) connect();
    // Strict Cleanup: Ensure all listeners and sockets are destroyed when hook unmounts 
    // or dependencies change, preventing ghost connections in Strict Mode.
    return () => cleanup();
  }, [relayUrl, shareCode, enabled, connect]);

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
