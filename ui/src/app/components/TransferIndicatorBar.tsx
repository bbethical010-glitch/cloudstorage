import { useEffect, useRef, useState, useCallback } from 'react'
import { useNavigate } from 'react-router'

declare global {
    interface Window {
        Android?: any;
    }
}

interface TransferStatus {
    transferId: string
    fileName: string
    totalBytes: number
    bytesWritten: number
    progressPercent: number
    speedBytesPerSecond: number
    isActive: boolean
    isComplete: boolean
    isFailed: boolean
    startedAt: number
    errorMessage?: string
}

function formatSpeed(bytesPerSecond: number): string {
    if (bytesPerSecond >= 1_048_576)
        return `${(bytesPerSecond / 1_048_576).toFixed(1)} MB/s`
    if (bytesPerSecond >= 1024)
        return `${(bytesPerSecond / 1024).toFixed(0)} KB/s`
    return `${bytesPerSecond} B/s`
}

export function TransferIndicatorBar() {
    const navigate = useNavigate()
    const [transfers, setTransfers] = useState<TransferStatus[]>([])
    const [showCompletionFlash, setShowCompletionFlash] = useState(false)
    const [animatedProgress, setAnimatedProgress] = useState(0)
    const previousActiveCount = useRef(0)
    const animFrameRef = useRef<number | null>(null)

    // Determine the correct API base URL
    const getApiBase = useCallback((): string => {
        if (window.Android) return 'http://127.0.0.1:8080'
        return ''
    }, [])

    const getHeaders = useCallback(() => {
        const token = localStorage.getItem('cloud_storage_token') || localStorage.getItem('cloud_storage_android_token') || '';
        const params = new URLSearchParams(window.location.hash.split('?')[1]);
        const pwd = params.get('pwd') || token;
        
        // Extract node ID if present in the URL (for relay mode)
        const shareCode = window.location.pathname.split('/')[2] || "";

        return {
            'Authorization': `Bearer ${pwd}`,
            ...(shareCode ? { 'X-Node-Id': shareCode } : {}),
            'Accept': 'application/json'
        };
    }, [])

    // Poll /api/transfer_status every 1000ms (throttled from 500ms)
    useEffect(() => {
        let cancelled = false
        const apiBase = getApiBase()

        async function poll() {
            try {
                const res = await fetch(`${apiBase}/api/transfer_status`, {
                    headers: getHeaders()
                })
                if (!res.ok) return
                const data: TransferStatus[] = await res.json()
                if (!cancelled) {
                    // Only show transfers that are active, or finished/failed within the last 3-5 seconds
                    // The backend TransferRegistry already performs cleanup, but we filter here for safety.
                    setTransfers(data)
                }
            } catch {
                // Silently ignore polling errors
            }
        }

        const interval = setInterval(poll, 1000)
        poll() // Immediate first poll

        return () => {
            cancelled = true
            clearInterval(interval)
        }
    }, [getApiBase, getHeaders])

    // Detect transition from active → empty for completion flash
    useEffect(() => {
        const currentActiveCount = transfers.length
        if (previousActiveCount.current > 0 && currentActiveCount === 0) {
            setShowCompletionFlash(true)
            const timer = setTimeout(() => setShowCompletionFlash(false), 600)
            return () => clearTimeout(timer)
        }
        previousActiveCount.current = currentActiveCount
    }, [transfers])

    // Smooth progress animation via requestAnimationFrame
    const primaryTransfer = transfers[0]
    const targetProgress = primaryTransfer?.progressPercent ?? 0

    useEffect(() => {
        const animate = () => {
            setAnimatedProgress(prev => {
                const diff = targetProgress - prev
                if (Math.abs(diff) < 0.5) return targetProgress
                return prev + diff * 0.15
            })
            animFrameRef.current = requestAnimationFrame(animate)
        }
        animFrameRef.current = requestAnimationFrame(animate)
        return () => {
            if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current)
        }
    }, [targetProgress])

    const isVisible = transfers.length > 0 || showCompletionFlash
    const additionalCount = transfers.length - 1

    const progressColor = showCompletionFlash ? '#22C55E' : '#3B82F6'
    const bgColor = showCompletionFlash
        ? 'rgba(34, 197, 94, 0.08)'
        : 'rgba(15, 17, 23, 0.98)'

    return (
        <div
            style={{
                width: '100%',
                height: isVisible ? '36px' : '0px',
                overflow: 'hidden',
                background: bgColor,
                borderBottom: isVisible
                    ? `1px solid ${showCompletionFlash ? 'rgba(34,197,94,0.2)' : '#1C2035'}`
                    : 'none',
                cursor: isVisible ? 'pointer' : 'default',
                position: 'relative',
                flexShrink: 0,
                transition: [
                    'height 350ms cubic-bezier(0.16, 1, 0.3, 1)',
                    'background 300ms ease',
                    'border-color 300ms ease'
                ].join(', '),
            }}
            onClick={() => isVisible && navigate('/transfers')}
        >
            {isVisible && primaryTransfer && (
                <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    height: '34px',
                    padding: '0 16px',
                    gap: '8px',
                }}>
                    {/* Pulsing upload icon */}
                    <PulsingUploadIcon color={progressColor} />

                    {/* Filename */}
                    <span style={{
                        fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                        fontSize: '11px',
                        color: '#7A8099',
                        flex: 1,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        minWidth: 0,
                    }}>
                        {primaryTransfer.fileName}
                    </span>

                    {/* Speed */}
                    {primaryTransfer.speedBytesPerSecond > 0 && (
                        <span style={{
                            fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                            fontSize: '10px',
                            color: '#3A3F58',
                            whiteSpace: 'nowrap',
                            flexShrink: 0,
                        }}>
                            {formatSpeed(primaryTransfer.speedBytesPerSecond)}
                        </span>
                    )}

                    {/* Percentage */}
                    <span style={{
                        fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                        fontSize: '11px',
                        color: primaryTransfer.isFailed ? '#EF4444' : progressColor,
                        fontWeight: 500,
                        whiteSpace: 'nowrap',
                        flexShrink: 0,
                        transition: 'color 300ms ease',
                        minWidth: '36px',
                        textAlign: 'right',
                    }}>
                        {primaryTransfer.isFailed ? 'FAILED' 
                            : (primaryTransfer.isComplete || showCompletionFlash) ? '100%' 
                            : `${primaryTransfer.progressPercent}%`}
                    </span>

                    {/* +N for multiple transfers */}
                    {additionalCount > 0 && (
                        <span style={{
                            fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                            fontSize: '10px',
                            color: '#3A3F58',
                            flexShrink: 0,
                        }}>
                            +{additionalCount}
                        </span>
                    )}
                </div>
            )}

            {/* Completion flash state — no primary transfer but still visible */}
            {isVisible && !primaryTransfer && showCompletionFlash && (
                <div style={{
                    display: 'flex',
                    alignItems: 'center',
                    height: '34px',
                    padding: '0 16px',
                    gap: '8px',
                }}>
                    <span style={{ fontSize: '12px' }}>✓</span>
                    <span style={{
                        fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                        fontSize: '11px',
                        color: '#22C55E',
                    }}>
                        Upload complete
                    </span>
                </div>
            )}

            {/* Progress line at bottom edge */}
            {isVisible && (
                <div style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    height: '2px',
                    width: '100%',
                    background: '#1C2035',
                }}>
                    <div style={{
                        height: '100%',
                        width: `${showCompletionFlash ? 100 : animatedProgress}%`,
                        background: progressColor,
                        transition: 'background 300ms ease',
                        borderRadius: '0 2px 2px 0',
                        boxShadow: `0 0 6px ${progressColor}`,
                    }} />
                </div>
            )}
        </div>
    )
}

function PulsingUploadIcon({ color }: { color: string }) {
    return (
        <>
            <style>{`
                @keyframes uploadPulse {
                    0%, 100% { opacity: 0.4; transform: translateY(0); }
                    50% { opacity: 1; transform: translateY(-1px); }
                }
                .upload-pulse-icon {
                    animation: uploadPulse 900ms ease-in-out infinite;
                    flex-shrink: 0;
                }
            `}</style>
            <svg
                className="upload-pulse-icon"
                width="12"
                height="12"
                viewBox="0 0 24 24"
                fill="none"
                stroke={color}
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                style={{ transition: 'stroke 300ms ease' }}
            >
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="17 8 12 3 7 8" />
                <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
        </>
    )
}
