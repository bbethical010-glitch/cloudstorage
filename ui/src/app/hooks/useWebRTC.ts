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

import { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { P2PTransport } from './p2pTransport';

export type P2PConnectionState = 'disconnected' | 'connecting' | 'signaling' | 'ice-gathering' | 'dc-opening' | 'connected' | 'fallback' | 'failed';

// Public STUN servers for NAT traversal
const ICE_SERVERS: RTCIceServer[] = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
  { urls: 'turn:openrelay.metered.ca:80', username: 'openrelayproject', credential: 'openrelayproject' }
];

interface UseWebRTCOptions {
  relayUrl: string;
  shareCode: string;
  enabled?: boolean;
}

interface UseWebRTCReturn {
  connectionState: P2PConnectionState;
  transport: P2PTransport | null;
  isReady: boolean;
  isDataChannelReady: boolean;
  reconnect: () => void;
}

export function useWebRTC({ relayUrl, shareCode, enabled = true }: UseWebRTCOptions): UseWebRTCReturn {
  const [connectionState, setConnectionState] = useState<P2PConnectionState>(enabled ? 'connecting' : 'disconnected');
  const [isReady, setIsReady] = useState(false);
  const [isDataChannelReady, setIsDataChannelReady] = useState(false);
  const transportRef = useRef<P2PTransport>(new P2PTransport());
  const pcRef = useRef<RTCPeerConnection | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const dcRef = useRef<RTCDataChannel | null>(null);
  const reconnectRef = useRef<number>(0);
  const connectionStateRef = useRef<P2PConnectionState>(connectionState);
  
  // Strict Stage Timeouts
  const initTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const offerTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const iceTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const dcTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  
  const iceCandidateQueueRef = useRef<RTCIceCandidateInit[]>([]);

  useEffect(() => {
    connectionStateRef.current = connectionState;
  }, [connectionState]);

  const clearAllTimeouts = useCallback(() => {
    if (initTimeoutRef.current) { clearTimeout(initTimeoutRef.current); initTimeoutRef.current = null; }
    if (offerTimeoutRef.current) { clearTimeout(offerTimeoutRef.current); offerTimeoutRef.current = null; }
    if (iceTimeoutRef.current) { clearTimeout(iceTimeoutRef.current); iceTimeoutRef.current = null; }
    if (dcTimeoutRef.current) { clearTimeout(dcTimeoutRef.current); dcTimeoutRef.current = null; }
  }, []);

  const failAndFallback = useCallback((reason: string) => {
    console.warn(`[PC_DEBUG] HARD_FAIL: ${reason}. Triggering fallbackToRelay().`);
    setConnectionState('fallback');
    clearAllTimeouts();
  }, [clearAllTimeouts]);

  const cleanup = useCallback(() => {
    clearAllTimeouts();

    if (dcRef.current) {
      dcRef.current.onopen = null;
      dcRef.current.onclose = null;
      dcRef.current.close();
      dcRef.current = null;
    }
    
    if (pcRef.current) {
      pcRef.current.onicecandidate = null;
      pcRef.current.onconnectionstatechange = null;
      pcRef.current.oniceconnectionstatechange = null;
      pcRef.current.close();
      pcRef.current = null;
    }
    
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
    setIsDataChannelReady(false);
  }, [clearAllTimeouts]);

  const connect = useCallback(() => {
    try {
      if (!enabled || !relayUrl || !shareCode) return;

      cleanup();
      setConnectionState('connecting');

      // Hard fail-safe: if we don't reach 'connected' or at least 'ice-gathering' quickly
      if (initTimeoutRef.current) clearTimeout(initTimeoutRef.current);
      initTimeoutRef.current = setTimeout(() => {
        if (wsRef.current?.readyState !== WebSocket.OPEN) {
          console.error("WEBRTC_INIT_ERROR: WebSocket never opened.");
          failAndFallback('HARD_INIT_WS_TIMEOUT');
        } else if (pcRef.current == null) {
           console.error("WEBRTC_INIT_ERROR: PeerConnection not initialized.");
           failAndFallback('HARD_INIT_PC_TIMEOUT');
        }
      }, 3000);

      const wsUrl = relayUrl
        .replace('https://', 'wss://')
        .replace('http://', 'ws://') + `/signal/${shareCode.toUpperCase()}`;

      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      ws.onopen = () => {
        setConnectionState('signaling');
        initiatePeerConnection(ws);
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          handleSignalingMessage(msg);
        } catch (e) {
          console.error('[SIGNAL_DEBUG] Parse error:', e);
        }
      };

      ws.onerror = (err) => {
        console.error('[SIGNAL_DEBUG] WS_ERROR:', err);
        failAndFallback('SIGNAL_WS_ERROR');
      };

      ws.onclose = () => {
        if (wsRef.current === ws) {
          if (connectionStateRef.current !== 'connected' && connectionStateRef.current !== 'fallback') {
            setConnectionState('disconnected');
          }
        }
      };
    } catch (e) {
      console.error("WEBRTC_INIT_ERROR", e);
      failAndFallback('SYNC_INIT_CATCH');
    }
  }, [cleanup, relayUrl, shareCode, enabled, failAndFallback]);

  function initiatePeerConnection(ws: WebSocket) {
    console.log('[PC_DEBUG] INITIALIZING');
    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    pcRef.current = pc;
    iceCandidateQueueRef.current = [];

    // Stage 1: DataChannel creation BEFORE offer
    console.log('[DC_DEBUG] CREATING "files"');
    const dc = pc.createDataChannel('files', { ordered: true });
    dcRef.current = dc;
    transportRef.current.attach(dc);

    dc.onopen = () => {
      console.log('[DC_DEBUG] DATA_CHANNEL_OPEN — P2P Live');
      clearAllTimeouts();
      setConnectionState('connected');
      setIsReady(true);
      setIsDataChannelReady(true);
    };

    dc.onclose = () => {
      console.log('[DC_DEBUG] DATA_CHANNEL_CLOSE');
      setIsReady(false);
      setIsDataChannelReady(false);
    };

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
        console.log('[SIGNAL_DEBUG] SENT_ICE');
      }
    };

    pc.onconnectionstatechange = () => {
      console.log('[PC_DEBUG] STATE:', pc.connectionState);
      if (pc.connectionState === 'failed') {
        failAndFallback('PC_STATE_FAILED');
      }
    };

    pc.oniceconnectionstatechange = () => {
      console.log('[ICE_DEBUG] STATE:', pc.iceConnectionState);
      if (pc.iceConnectionState === 'connected' || pc.iceConnectionState === 'completed') {
        console.log('[ICE_DEBUG] SUCCESS: Signaling transition to DC_OPENING');
        if (iceTimeoutRef.current) { clearTimeout(iceTimeoutRef.current); iceTimeoutRef.current = null; }
        setConnectionState('dc-opening');
        
        // Timeout 3: DataChannel must open within 6s of ICE success
        dcTimeoutRef.current = setTimeout(() => {
          if (dc.readyState !== 'open') failAndFallback('FAIL_DC_TIMEOUT');
        }, 6000);
      }
    };

    // Stage 2: Create Offer
    pc.createOffer().then((offer) => {
      return pc.setLocalDescription(offer).then(() => {
        const signal = {
          type: 'signal',
          signal: { type: 'offer', sdp: offer.sdp },
        };
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(signal));
          console.log('[SIGNAL_DEBUG] SENT_OFFER');
          
          // Timeout 1: SDP Answer must arrive within 3s
          offerTimeoutRef.current = setTimeout(() => {
            if (pc.signalingState === 'have-local-offer') failAndFallback('FAIL_SIGNAL_TIMEOUT');
          }, 3000);
        }
      });
    }).catch((err) => {
      console.error('[PC_DEBUG] CREATE_OFFER_ERROR:', err);
      failAndFallback('PC_INIT_ERROR');
    });
  }

  function handleSignalingMessage(msg: any) {
    const pc = pcRef.current;
    if (!pc) return;

    if (msg.type === 'signal' && msg.signal) {
      const signal = msg.signal;

      if (signal.type === 'answer') {
        if (pc.signalingState !== 'have-local-offer') {
          console.warn('[SIGNAL_DEBUG] Unexpected answer in state:', pc.signalingState);
          return;
        }

        console.log('[SIGNAL_DEBUG] RECEIVED_ANSWER');
        if (offerTimeoutRef.current) { clearTimeout(offerTimeoutRef.current); offerTimeoutRef.current = null; }
        setConnectionState('ice-gathering');

        // Timeout 2: ICE must connect within 5s of receiving answer
        iceTimeoutRef.current = setTimeout(() => {
          if (pc.iceConnectionState !== 'connected' && pc.iceConnectionState !== 'completed') {
            failAndFallback('FAIL_ICE_TIMEOUT');
          }
        }, 5000);

        pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp: signal.sdp })).then(() => {
          console.log('[PC_DEBUG] REMOTE_SDP_SET');
          iceCandidateQueueRef.current.forEach(c => pc.addIceCandidate(new RTCIceCandidate(c)));
          iceCandidateQueueRef.current = [];
        }).catch((err) => {
          console.error('[PC_DEBUG] SET_REMOTE_ERROR:', err);
          failAndFallback('SDP_SET_ERROR');
        });

      } else if (signal.type === 'ice') {
        console.log('[SIGNAL_DEBUG] RECEIVED_ICE');
        const candidateInit = {
          candidate: signal.candidate,
          sdpMid: signal.sdpMid,
          sdpMLineIndex: signal.sdpMLineIndex,
        };

        if (!pc.remoteDescription) {
          iceCandidateQueueRef.current.push(candidateInit);
          return;
        }

        pc.addIceCandidate(new RTCIceCandidate(candidateInit)).catch((err) => {
          console.warn('[ICE_DEBUG] CANDIDATE_ADD_ERROR:', err);
        });
      }
    }
  }

  useEffect(() => {
    if (enabled) {
      connect();
    } else {
      setConnectionState('disconnected');
      cleanup();
    }
    return () => cleanup();
  }, [enabled, connect, cleanup]);

  const reconnect = useCallback(() => {
    reconnectRef.current = 0;
    connect();
  }, [connect]);

  return useMemo(
    () => ({
      connectionState,
      transport: transportRef.current,
      isReady,
      isDataChannelReady,
      reconnect,
    }),
    [connectionState, isReady, isDataChannelReady, reconnect]
  );
}
