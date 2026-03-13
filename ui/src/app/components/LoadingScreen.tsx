import { motion } from "motion/react";
import { Server, HardDrive, Cpu, Wifi } from "lucide-react";
import { useState, useEffect } from "react";

const messages = [
  "Preparing your storage...",
  "Getting things ready...",
  "Almost there..."
];

export function LoadingScreen({ onComplete }: { onComplete: () => void }) {
  const [msgIndex, setMsgIndex] = useState(0);

  useEffect(() => {
    const timer = setInterval(() => {
      setMsgIndex((prev) => {
        if (prev < messages.length - 1) return prev + 1;
        clearInterval(timer);
        setTimeout(onComplete, 1000);
        return prev;
      });
    }, 1500);
    return () => clearInterval(timer);
  }, [onComplete]);

  return (
    <div className="fixed inset-0 bg-[#0B1220] flex flex-col items-center justify-center z-[100]">
      {/* Network Diagram Animation */}
      <div className="relative w-80 h-64 mb-12">
        {/* Connection Lines */}
        <svg className="absolute inset-0 w-full h-full">
          <motion.path
            d="M 40 160 L 160 160 L 280 160"
            stroke="#2563EB"
            strokeWidth="2"
            fill="none"
            initial={{ pathLength: 0 }}
            animate={{ pathLength: 1 }}
            transition={{ duration: 2, repeat: Infinity }}
          />
          <motion.path
            d="M 160 160 L 160 60"
            stroke="#A855F7"
            strokeWidth="2"
            fill="none"
            initial={{ pathLength: 0 }}
            animate={{ pathLength: 1 }}
            transition={{ duration: 2, repeat: Infinity, delay: 0.5 }}
          />
        </svg>

        {/* External Drive */}
        <motion.div 
          className="absolute left-0 bottom-12 flex flex-col items-center"
          initial={{ opacity: 0, scale: 0.8 }}
          animate={{ opacity: 1, scale: 1 }}
        >
          <div className="p-3 bg-[#1F2937] border border-[#374151] rounded-xl shadow-lg">
            <HardDrive className="w-8 h-8 text-[#E5E7EB]" />
          </div>
          <span className="text-[10px] font-mono text-[#9CA3AF] mt-2">DRIVE</span>
        </motion.div>

        {/* Phone Gateway (Center) */}
        <motion.div 
          className="absolute left-1/2 -translate-x-1/2 bottom-12 flex flex-col items-center"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.3 }}
        >
          <div className="p-4 bg-[#2563EB]/10 border border-[#2563EB]/40 rounded-2xl shadow-2xl relative">
            <Server className="w-10 h-10 text-[#2563EB]" />
            <motion.div 
              className="absolute -inset-1 rounded-2xl border border-[#2563EB]/20"
              animate={{ scale: [1, 1.2, 1], opacity: [0.5, 0, 0.5] }}
              transition={{ duration: 2, repeat: Infinity }}
            />
          </div>
          <span className="text-xs font-bold text-[#E5E7EB] mt-3 tracking-widest">GATEWAY</span>
        </motion.div>

        {/* Relay Cloud (Top) */}
        <motion.div 
          className="absolute left-1/2 -translate-x-1/2 top-0 flex flex-col items-center"
          initial={{ opacity: 0, y: -20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.6 }}
        >
          <div className="p-3 bg-[#A855F7]/10 border border-[#A855F7]/40 rounded-full">
            <Wifi className="w-7 h-7 text-[#A855F7]" />
          </div>
          <span className="text-[10px] font-mono text-[#A855F7] mt-2">RELAY</span>
        </motion.div>

        {/* Client (Right) */}
        <motion.div 
          className="absolute right-0 bottom-12 flex flex-col items-center"
          initial={{ opacity: 0, scale: 0.8 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ delay: 0.9 }}
        >
          <div className="p-3 bg-[#1F2937] border border-[#374151] rounded-xl">
            <Cpu className="w-8 h-8 text-[#22C55E]" />
          </div>
          <span className="text-[10px] font-mono text-[#9CA3AF] mt-2">BROWSER</span>
        </motion.div>
      </div>

      {/* Logo & Status */}
      <div className="text-center">
        <h1 className="text-2xl font-bold text-[#E5E7EB] tracking-tight mb-4 flex items-center justify-center gap-3">
          <div className="w-8 h-8 bg-[#2563EB] rounded-lg flex items-center justify-center">
            <Server className="w-5 h-5 text-white" />
          </div>
          Easy Storage Cloud
        </h1>
        
        <div className="h-6 overflow-hidden">
          <motion.p
            key={msgIndex}
            initial={{ y: 20, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: -20, opacity: 0 }}
            className="text-sm font-mono text-[#9CA3AF]"
          >
            {messages[msgIndex]}
          </motion.p>
        </div>

        {/* Progress Bar */}
        <div className="w-48 h-1 bg-[#1F2937] rounded-full mt-6 mx-auto overflow-hidden">
          <motion.div 
            className="h-full bg-[#2563EB]"
            initial={{ width: "0%" }}
            animate={{ width: `${((msgIndex + 1) / messages.length) * 100}%` }}
            transition={{ duration: 0.5 }}
          />
        </div>
      </div>
    </div>
  );
}
