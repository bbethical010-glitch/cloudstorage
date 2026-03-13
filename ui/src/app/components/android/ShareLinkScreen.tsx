import { motion } from "motion/react";
import { Copy, QrCode, Share2, Shield, Calendar, Clock, ChevronLeft } from "lucide-react";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Input } from "../ui/input";
import { Switch } from "../ui/switch";
import { useNavigate } from "react-router";
import { toast } from "sonner";

export function ShareLinkScreen() {
  const navigate = useNavigate();
  const shareUrl = "https://easystorage/abc123";

  const copyUrl = () => {
    navigator.clipboard.writeText(shareUrl);
    toast.success("Link copied to clipboard");
  };

  return (
    <div className="min-h-screen bg-[#0B1220] text-[#E5E7EB] p-6 pb-32">
      <div className="flex items-center gap-4 mb-8">
        <Button 
          variant="ghost" 
          size="icon" 
          onClick={() => navigate(-1)}
          className="rounded-full hover:bg-[#1F2937]"
        >
          <ChevronLeft className="w-6 h-6" />
        </Button>
        <h1 className="text-xl font-bold">Share Access</h1>
      </div>

      <div className="space-y-6">
        <Card className="bg-[#111827] border-[#374151] p-6">
          <p className="text-xs font-bold text-[#9CA3AF] uppercase tracking-widest mb-4">Your Storage Link</p>
          <div className="flex items-center gap-3 bg-[#0B1220] p-4 rounded-xl border border-[#374151] mb-6">
            <span className="flex-1 text-sm font-medium truncate">{shareUrl}</span>
            <Button variant="ghost" size="icon" onClick={copyUrl} className="text-[#2563EB]">
              <Copy className="w-4 h-4" />
            </Button>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <Button className="h-12 bg-[#2563EB] hover:bg-[#1d4ed8] text-white rounded-xl font-bold flex gap-2">
              <Share2 className="w-4 h-4" />
              Share Link
            </Button>
            <Button variant="outline" className="h-12 border-[#374151] bg-transparent text-white rounded-xl font-bold flex gap-2">
              <QrCode className="w-4 h-4" />
              QR Code
            </Button>
          </div>
        </Card>

        <Card className="bg-[#111827] border-[#374151] p-6 space-y-6">
          <h3 className="text-sm font-bold tracking-widest text-[#9CA3AF] uppercase">Security Settings</h3>
          
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-[#2563EB]/10 flex items-center justify-center">
                <Shield className="w-5 h-5 text-[#2563EB]" />
              </div>
              <div>
                <p className="text-sm font-bold">Password Protect</p>
                <p className="text-xs text-[#9CA3AF]">Require password to access</p>
              </div>
            </div>
            <Switch />
          </div>

          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-[#A855F7]/10 flex items-center justify-center">
                <Calendar className="w-5 h-5 text-[#A855F7]" />
              </div>
              <div>
                <p className="text-sm font-bold">Link Expiration</p>
                <p className="text-xs text-[#9CA3AF]">Link expires in 24 hours</p>
              </div>
            </div>
            <Button variant="ghost" size="sm" className="text-[#A855F7] text-xs font-bold">CHANGE</Button>
          </div>
        </Card>
      </div>

      {/* Decorative background element */}
      <div className="absolute bottom-0 left-0 w-full h-64 bg-gradient-to-t from-[#2563EB]/5 to-transparent pointer-events-none" />
    </div>
  );
}
