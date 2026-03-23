import { motion, AnimatePresence } from "motion/react";
import QRCode from "react-qr-code";
import { useState } from "react";
import {
  X,
  Globe,
  Wifi,
  Link2,
  Copy,
  Share2,
  Check,
  ExternalLink
} from "lucide-react";
import { Button } from "../ui/button";
import { androidBridge } from "../../bridge";
import { toast } from "sonner";

type Tab = "relay" | "lan" | "invite";

interface ShareQRDialogProps {
  open: boolean;
  onClose: () => void;
  relayBaseUrl: string;
  shareCode: string;
  lanUrl?: string;
  tunnelConnected: boolean;
}

const TABS: { id: Tab; label: string; icon: React.ReactNode; color: string }[] = [
  { id: "relay", label: "Relay", icon: <Globe className="w-4 h-4" />, color: "#A855F7" },
  { id: "lan", label: "LAN", icon: <Wifi className="w-4 h-4" />, color: "#10B981" },
  { id: "invite", label: "Invite", icon: <Link2 className="w-4 h-4" />, color: "#F59E0B" },
];

export function ShareQRDialog({ open, onClose, relayBaseUrl, shareCode, lanUrl, tunnelConnected }: ShareQRDialogProps) {
  const [activeTab, setActiveTab] = useState<Tab>("relay");
  const [copiedField, setCopiedField] = useState<string | null>(null);

  const relayUrl = relayBaseUrl
    ? `https://${relayBaseUrl.replace(/^https?:\/\//, '')}/node/${shareCode}`
    : "";
  const inviteUrl = `easystoragecloud://join?code=${shareCode}`;

  const getQRValue = () => {
    switch (activeTab) {
      case "relay": return relayUrl;
      case "lan": return lanUrl || "";
      case "invite": return inviteUrl;
    }
  };

  const getDisplayUrl = () => {
    switch (activeTab) {
      case "relay": return relayUrl;
      case "lan": return lanUrl || "Not available";
      case "invite": return inviteUrl;
    }
  };

  const getTabDescription = () => {
    switch (activeTab) {
      case "relay": return "Access your storage from anywhere via the cloud relay. Works on any network.";
      case "lan": return "Direct connection over your local WiFi network. Faster speeds, no internet needed.";
      case "invite": return "Share this deep link to let others open your node directly in the Easy Storage app.";
    }
  };

  const isTabAvailable = (tab: Tab) => {
    if (tab === "relay") return tunnelConnected && !!relayUrl;
    if (tab === "lan") return !!lanUrl;
    return true;
  };

  const handleCopy = () => {
    const url = getDisplayUrl();
    if (!url || url === "Not available") return;
    androidBridge.copyToClipboard(url, "Link copied!");
    setCopiedField(activeTab);
    setTimeout(() => setCopiedField(null), 2000);
  };

  const handleShare = () => {
    const url = getDisplayUrl();
    if (!url || url === "Not available") return;
    
    let shareText = "";
    switch (activeTab) {
      case "relay":
        shareText = `🌐 Access my Easy Storage Cloud:\n${url}\n\nOpen this link in any browser to access my files.`;
        break;
      case "lan":
        shareText = `📡 Connect to my Easy Storage on local network:\n${url}\n\nNote: You must be on the same WiFi network.`;
        break;
      case "invite":
        shareText = `🔗 Join my Easy Storage Cloud node:\n${url}\n\nOpen this link in the Easy Storage Cloud app.`;
        break;
    }
    androidBridge.shareLink(shareText);
  };

  const qrValue = getQRValue();
  const available = isTabAvailable(activeTab);

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-[100] flex items-end justify-center bg-black/70 backdrop-blur-sm"
          onClick={onClose}
        >
          <motion.div
            initial={{ y: "100%" }}
            animate={{ y: 0 }}
            exit={{ y: "100%" }}
            transition={{ type: "spring", damping: 28, stiffness: 300 }}
            className="w-full max-w-md bg-[#111827] border-t border-[#1F2937] rounded-t-[2rem] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Handle Bar */}
            <div className="flex justify-center pt-3 pb-1">
              <div className="w-10 h-1 rounded-full bg-[#374151]" />
            </div>

            {/* Header */}
            <div className="flex items-center justify-between px-6 py-3">
              <h2 className="text-lg font-bold text-white tracking-tight">Share & Connect</h2>
              <Button
                variant="ghost"
                size="icon"
                onClick={onClose}
                className="rounded-full hover:bg-[#1F2937] h-9 w-9"
              >
                <X className="w-5 h-5 text-[#9CA3AF]" />
              </Button>
            </div>

            {/* Tabs */}
            <div className="flex gap-2 px-6 pb-4">
              {TABS.map((tab) => {
                const isActive = activeTab === tab.id;
                const isAvail = isTabAvailable(tab.id);
                return (
                  <button
                    key={tab.id}
                    onClick={() => isAvail && setActiveTab(tab.id)}
                    className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 ${
                      isActive
                        ? "text-white shadow-lg"
                        : isAvail
                        ? "bg-[#0B1220] text-[#9CA3AF] hover:text-white"
                        : "bg-[#0B1220]/50 text-[#4B5563] cursor-not-allowed"
                    }`}
                    style={isActive ? { background: `${tab.color}20`, color: tab.color, boxShadow: `0 0 20px ${tab.color}15` } : {}}
                  >
                    {tab.icon}
                    {tab.label}
                    {!isAvail && (
                      <span className="text-[8px] bg-red-500/20 text-red-400 px-1.5 py-0.5 rounded-full font-bold">OFF</span>
                    )}
                  </button>
                );
              })}
            </div>

            {/* Content */}
            <div className="px-6 pb-6">
              {/* Description */}
              <p className="text-[11px] text-[#9CA3AF] text-center mb-5 leading-relaxed">
                {getTabDescription()}
              </p>

              {/* QR Code */}
              {available && qrValue ? (
                <motion.div
                  key={activeTab}
                  initial={{ opacity: 0, scale: 0.9 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={{ duration: 0.25 }}
                  className="flex justify-center mb-5"
                >
                  <div className="bg-white p-4 rounded-2xl shadow-[0_0_40px_rgba(37,99,235,0.08)]">
                    <QRCode
                      value={qrValue}
                      size={180}
                      level="Q"
                      bgColor="#ffffff"
                      fgColor="#111827"
                    />
                  </div>
                </motion.div>
              ) : (
                <div className="flex flex-col items-center justify-center py-10 mb-5">
                  <div className="w-16 h-16 rounded-full bg-[#1F2937] flex items-center justify-center mb-3">
                    {activeTab === "relay" ? (
                      <Globe className="w-7 h-7 text-[#4B5563]" />
                    ) : (
                      <Wifi className="w-7 h-7 text-[#4B5563]" />
                    )}
                  </div>
                  <p className="text-sm font-bold text-[#6B7280]">
                    {activeTab === "relay" ? "Cloud Tunnel Offline" : "No LAN Address"}
                  </p>
                  <p className="text-[11px] text-[#4B5563] mt-1">
                    {activeTab === "relay" ? "Start the node and wait for tunnel connection." : "Connect to a WiFi network first."}
                  </p>
                </div>
              )}

              {/* URL Display */}
              {available && qrValue && (
                <div className="bg-[#0B1220] border border-[#1F2937] rounded-xl p-3 mb-4 flex items-center gap-2">
                  <ExternalLink className="w-3.5 h-3.5 text-[#6B7280] shrink-0" />
                  <span className="text-[11px] font-mono text-[#9CA3AF] break-all flex-1 leading-relaxed">
                    {getDisplayUrl()}
                  </span>
                </div>
              )}

              {/* Action Buttons */}
              {available && qrValue && (
                <div className="flex gap-3">
                  <Button
                    onClick={handleCopy}
                    className="flex-1 h-12 rounded-xl bg-[#1F2937] hover:bg-[#374151] text-white text-sm font-bold flex items-center justify-center gap-2 transition-colors"
                  >
                    {copiedField === activeTab ? (
                      <><Check className="w-4 h-4 text-[#10B981]" /> Copied!</>
                    ) : (
                      <><Copy className="w-4 h-4" /> Copy Link</>
                    )}
                  </Button>
                  <Button
                    onClick={handleShare}
                    className="flex-1 h-12 rounded-xl bg-gradient-to-r from-[#2563EB] to-[#A855F7] hover:opacity-90 text-white text-sm font-bold flex items-center justify-center gap-2 transition-opacity shadow-lg shadow-purple-500/20"
                  >
                    <Share2 className="w-4 h-4" /> Share
                  </Button>
                </div>
              )}
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
