import { useEffect, useState, useCallback } from 'react'
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
    errorMessage?: string
}

function formatBytes(bytes: number): string {
    if (bytes >= 1_073_741_824) return `${(bytes / 1_073_741_824).toFixed(1)} GB`
    if (bytes >= 1_048_576) return `${(bytes / 1_048_576).toFixed(1)} MB`
    if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${bytes} B`
}

function formatSpeed(bytesPerSecond: number): string {
    if (bytesPerSecond >= 1_048_576)
        return `${(bytesPerSecond / 1_048_576).toFixed(1)} MB/s`
    if (bytesPerSecond >= 1024)
        return `${(bytesPerSecond / 1024).toFixed(0)} KB/s`
    return `${bytesPerSecond} B/s`
}

export function TransfersPage() {
    const navigate = useNavigate()
    const [transfers, setTransfers] = useState<TransferStatus[]>([])

    const getApiBase = useCallback((): string => {
        if (window.Android) return 'http://127.0.0.1:8080'
        return ''
    }, [])

    const getHeaders = useCallback(() => {
        const token = localStorage.getItem('cloud_storage_token') || localStorage.getItem('cloud_storage_android_token') || '';
        const params = new URLSearchParams(window.location.hash.split('?')[1]);
        const pwd = params.get('pwd') || token;
        
        // Extract node ID from the current URL if possible
        const shareCode = window.location.pathname.split('/')[2] || "";

        return {
            'Authorization': `Bearer ${pwd}`,
            ...(shareCode ? { 'X-Node-Id': shareCode } : {})
        };
    }, [])

    useEffect(() => {
        let cancelled = false
        const apiBase = getApiBase()

        async function poll() {
            try {
                const res = await fetch(`${apiBase}/api/transfer_status`, {
                    headers: getHeaders()
                })
                if (!res.ok || cancelled) return
                const data = await res.json()
                if (!cancelled) setTransfers(data)
            } catch { /* ignore */ }
        }
        const interval = setInterval(poll, 1000)
        poll()
        return () => { cancelled = true; clearInterval(interval) }
    }, [getApiBase, getHeaders])

    return (
        <div style={{
            minHeight: '100vh',
            background: '#08090E',
            color: '#E2E5F0',
            fontFamily: 'Inter, sans-serif',
        }}>
            {/* Header */}
            <div style={{
                display: 'flex',
                alignItems: 'center',
                gap: '12px',
                padding: '16px 20px',
                borderBottom: '1px solid #1C2035',
                background: '#0F1117',
            }}>
                <button
                    onClick={() => navigate(-1)}
                    style={{
                        background: 'transparent',
                        border: 'none',
                        color: '#7A8099',
                        cursor: 'pointer',
                        padding: '4px',
                        display: 'flex',
                        alignItems: 'center',
                        fontSize: '18px',
                    }}
                >
                    ←
                </button>
                <h1 style={{
                    fontSize: '18px',
                    fontWeight: 600,
                    margin: 0,
                }}>
                    Transfers
                </h1>
                <span style={{
                    fontSize: '11px',
                    fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                    color: '#7A8099',
                    background: '#141720',
                    border: '1px solid #1C2035',
                    borderRadius: '10px',
                    padding: '2px 8px',
                }}>
                    {transfers.filter(t => t.isActive).length} active
                </span>
            </div>

            {/* Transfer list */}
            <div style={{ padding: '16px' }}>
                {transfers.length === 0 ? (
                    <div style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        justifyContent: 'center',
                        paddingTop: '80px',
                        gap: '12px',
                        color: '#3A3F58',
                    }}>
                        <svg width="40" height="40" viewBox="0 0 24 24" fill="none"
                            stroke="currentColor" strokeWidth="1.5">
                            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                            <polyline points="17 8 12 3 7 8"/>
                            <line x1="12" y1="3" x2="12" y2="15"/>
                        </svg>
                        <p style={{ fontSize: '14px', margin: 0 }}>
                            No active transfers
                        </p>
                    </div>
                ) : (
                    transfers.map(transfer => (
                        <div key={transfer.transferId} style={{
                            background: '#0F1117',
                            border: `1px solid ${transfer.isFailed ? 'rgba(239,68,68,0.3)' : '#1C2035'}`,
                            borderRadius: '10px',
                            padding: '14px 16px',
                            marginBottom: '10px',
                        }}>
                            <div style={{
                                display: 'flex',
                                justifyContent: 'space-between',
                                alignItems: 'center',
                                marginBottom: '8px',
                            }}>
                                <span style={{
                                    fontSize: '13px',
                                    fontWeight: 500,
                                    color: transfer.isFailed ? '#EF4444' : '#E2E5F0',
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap',
                                    flex: 1,
                                    marginRight: '12px',
                                }}>
                                    {transfer.fileName}
                                </span>
                                <span style={{
                                    fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                                    fontSize: '12px',
                                    color: transfer.isFailed ? '#EF4444'
                                        : transfer.isComplete ? '#22C55E'
                                        : '#3B82F6',
                                    flexShrink: 0,
                                }}>
                                    {transfer.isFailed ? 'FAILED'
                                        : transfer.isComplete ? 'DONE'
                                        : `${transfer.progressPercent}%`}
                                </span>
                            </div>

                            {/* Progress bar */}
                            <div style={{
                                height: '3px',
                                background: '#1C2035',
                                borderRadius: '2px',
                                overflow: 'hidden',
                                marginBottom: '6px',
                            }}>
                                <div style={{
                                    height: '100%',
                                    width: `${transfer.progressPercent}%`,
                                    background: transfer.isFailed ? '#EF4444'
                                        : transfer.isComplete ? '#22C55E'
                                        : 'linear-gradient(90deg, #3B82F6, #06B6D4)',
                                    transition: 'width 300ms ease',
                                    borderRadius: '2px',
                                }} />
                            </div>

                            {/* Metadata row */}
                            <div style={{
                                display: 'flex',
                                justifyContent: 'space-between',
                                fontSize: '10px',
                                fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
                                color: '#3A3F58',
                            }}>
                                <span>
                                    {formatBytes(transfer.bytesWritten)} / {formatBytes(transfer.totalBytes)}
                                </span>
                                {transfer.isActive && transfer.speedBytesPerSecond > 0 && (
                                    <span>{formatSpeed(transfer.speedBytesPerSecond)}</span>
                                )}
                                {transfer.errorMessage && (
                                    <span style={{ color: '#EF4444' }}>
                                        {transfer.errorMessage}
                                    </span>
                                )}
                            </div>
                        </div>
                    ))
                )}
            </div>
        </div>
    )
}
