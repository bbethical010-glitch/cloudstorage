import React from "react";
import { motion, AnimatePresence } from "motion/react";
import { 
  X, 
  Share2, 
  Download, 
  Trash2, 
  Info, 
  Calendar, 
  Database, 
  FileText,
  Image as ImageIcon,
  Film,
  Music,
  ExternalLink
} from "lucide-react";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Separator } from "../ui/separator";

interface FileDetailsProps {
  file: any;
  onClose: () => void;
  onDownload: () => void;
  onShare: () => void;
  onDelete: () => void;
}

export function FileDetails({ file, onClose, onDownload, onShare, onDelete }: FileDetailsProps) {
  const getFileIconLarge = (file: any) => {
    const ext = file.name.split('.').pop()?.toLowerCase();
    if (['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(ext)) 
      return <ImageIcon className="w-16 h-16 text-[#A855F7]" />;
    if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) 
      return <Film className="w-16 h-16 text-orange-400" />;
    if (['mp3', 'wav', 'flac'].includes(ext))
      return <Music className="w-16 h-16 text-pink-400" />;
    
    return <FileText className="w-16 h-16 text-[#9CA3AF]" />;
  };

  const [previewUrl, setPreviewUrl] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (file.rawFile) {
      const url = URL.createObjectURL(file.rawFile);
      setPreviewUrl(url);
      return () => URL.revokeObjectURL(url);
    } else {
      const token = localStorage.getItem('cloud_storage_android_token') || '';
      const path = encodeURIComponent(file.path || file.name);
      setPreviewUrl(`http://127.0.0.1:8080/api/file-content?path=${path}&token=${encodeURIComponent(token)}`);
    }
  }, [file]);

  const renderPreview = () => {
    const ext = file.name.split('.').pop()?.toLowerCase();
    
    if (['png', 'jpg', 'jpeg', 'webp', 'gif'].includes(ext)) {
      return <img src={previewUrl || ''} alt={file.name} className="w-full h-full object-contain" />;
    }
    if (['mp4', 'mov', 'webm'].includes(ext)) {
      return <video src={previewUrl || ''} controls className="w-full h-full object-contain" />;
    }
    if (ext === 'pdf') {
      return <iframe src={previewUrl || ''} className="w-full h-full bg-white" />;
    }

    return (
      <div className="flex flex-col items-center justify-center w-full h-48 text-[#9CA3AF]">
         {getFileIconLarge(file)}
         <span className="text-xs font-bold mt-4 text-center tracking-widest uppercase">Preview Unavailable</span>
      </div>
    );
  };

  return (
    <motion.div
      initial={{ y: "100%" }}
      animate={{ y: 0 }}
      exit={{ y: "100%" }}
      transition={{ type: "spring", damping: 25, stiffness: 200 }}
      className="fixed inset-0 z-[60] flex items-end justify-center"
    >
      <div className="absolute inset-0 bg-[#0B1220]/80 backdrop-blur-sm" onClick={onClose} />
      
      <Card className="w-full max-w-lg bg-[#111827] border-[#374151] rounded-t-[2.5rem] p-8 shadow-2xl relative z-10 max-h-[90vh] overflow-y-auto">
        <div className="flex justify-between items-start mb-8">
          <div className="w-12 h-1.5 bg-[#1F2937] rounded-full absolute top-4 left-1/2 -translate-x-1/2" />
          <div className="flex items-center gap-4 min-w-0 pr-4">
            <div className="w-12 h-12 rounded-xl bg-[#0B1220] border border-[#374151] flex items-center justify-center shrink-0">
              <FileText className="w-6 h-6 text-[#9CA3AF]" />
            </div>
            <div className="min-w-0 flex-1">
              <h2 className="text-xl font-bold truncate">{file.name}</h2>
              <p className="text-xs text-[#9CA3AF] uppercase tracking-widest font-mono mt-1 truncate">
                {file.size} • {file.type}
              </p>
            </div>
          </div>
          <Button 
            variant="ghost" 
            size="icon" 
            onClick={onClose}
            className="rounded-full bg-[#1F2937] hover:bg-[#374151] shrink-0"
          >
            <X className="w-5 h-5 text-[#E5E7EB]" />
          </Button>
        </div>

        {/* Media Preview Container */}
        {file.type !== "Folder" && (
          <div className="w-full mb-6 bg-[#0B1220] rounded-[1.5rem] border border-[#374151] overflow-hidden flex items-center justify-center min-h-[220px] relative mt-2 group">
             {renderPreview()}
          </div>
        )}

        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-4">
             <div className="p-4 bg-[#0B1220]/50 border border-[#374151]/50 rounded-2xl">
               <div className="flex items-center gap-2 text-[#9CA3AF] mb-1">
                 <Calendar className="w-3.5 h-3.5" />
                 <span className="text-[10px] font-bold uppercase tracking-wider">Modified</span>
               </div>
               <p className="text-sm font-medium">{file.modified || "Recently"}</p>
             </div>
             <div className="p-4 bg-[#0B1220]/50 border border-[#374151]/50 rounded-2xl">
               <div className="flex items-center gap-2 text-[#9CA3AF] mb-1">
                 <Database className="w-3.5 h-3.5" />
                 <span className="text-[10px] font-bold uppercase tracking-wider">Source</span>
               </div>
               <p className="text-sm font-medium">External Drive</p>
             </div>
          </div>

          <Separator className="bg-[#1F2937]" />

          <div className="space-y-3">
             <Button 
               onClick={onDownload}
               className="w-full h-14 bg-[#2563EB] hover:bg-[#1d4ed8] rounded-2xl text-lg font-bold flex gap-3"
             >
               <Download className="w-5 h-5" />
               Download File
             </Button>
             <div className="grid grid-cols-2 gap-3">
                <Button 
                  onClick={onShare}
                  variant="outline"
                  className="h-14 border-[#374151] bg-[#111827] rounded-2xl font-bold flex gap-2 hover:bg-[#1F2937]"
                >
                  <Share2 className="w-4 h-4 text-[#A855F7]" />
                  Share
                </Button>
                <Button 
                  onClick={onDelete}
                  variant="outline"
                  className="h-14 border-[#374151] bg-[#111827] rounded-2xl font-bold flex gap-2 hover:bg-[#1F2937] text-red-500 hover:text-red-400"
                >
                  <Trash2 className="w-4 h-4" />
                  Delete
                </Button>
             </div>
          </div>

          <div className="pt-4 flex justify-center">
             <button className="flex items-center gap-2 text-[10px] font-bold text-[#9CA3AF] hover:text-[#E5E7EB] uppercase tracking-[0.2em] transition-colors">
               <Info className="w-3.5 h-3.5" />
               Raw Metadata
             </button>
          </div>
        </div>
      </Card>
    </motion.div>
  );
}
