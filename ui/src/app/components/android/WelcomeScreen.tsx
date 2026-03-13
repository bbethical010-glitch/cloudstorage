import { motion } from "motion/react";
import { Server, ArrowRight } from "lucide-react";
import { Button } from "../ui/button";

interface WelcomeScreenProps {
  onStart: () => void;
  onSkip: () => void;
}

export function WelcomeScreen({ onStart, onSkip }: WelcomeScreenProps) {
  return (
    <div className="min-h-screen bg-[#0B1220] flex flex-col items-center justify-center p-6 text-center">
      <div className="absolute top-6 right-6">
        <Button 
          variant="ghost" 
          onClick={onSkip}
          className="text-[#9CA3AF] hover:text-white"
        >
          Skip Tutorial
        </Button>
      </div>

      <motion.div
        initial={{ opacity: 0, scale: 0.9, y: 20 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        className="mb-12"
      >
        <div className="w-24 h-24 bg-gradient-to-br from-[#2563EB] to-[#A855F7] rounded-[2rem] flex items-center justify-center shadow-2xl shadow-blue-500/20 mb-8 mx-auto">
          <Server className="w-12 h-12 text-white" />
        </div>
        
        <h1 className="text-3xl font-extrabold text-white mb-4 tracking-tight">
          Welcome to <br />
          <span className="text-transparent bg-clip-text bg-gradient-to-r from-[#2563EB] to-[#A855F7]">
            Easy Storage Cloud
          </span>
        </h1>
        
        <p className="text-lg text-[#9CA3AF] max-w-xs mx-auto leading-relaxed">
          Turn your external storage into a personal cloud that you can access anytime, anywhere.
        </p>
      </motion.div>

      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
        className="w-full max-w-xs space-y-4"
      >
        <Button 
          onClick={onStart}
          className="w-full h-14 bg-[#2563EB] hover:bg-[#1d4ed8] text-white rounded-2xl text-lg font-bold group"
        >
          Get Started
          <ArrowRight className="ml-2 w-5 h-5 group-hover:translate-x-1 transition-transform" />
        </Button>
      </motion.div>

      {/* Decorative background elements */}
      <div className="absolute top-1/4 -left-20 w-64 h-64 bg-[#2563EB]/5 blur-[100px] rounded-full pointer-events-none" />
      <div className="absolute bottom-1/4 -right-20 w-64 h-64 bg-[#A855F7]/5 blur-[100px] rounded-full pointer-events-none" />
    </div>
  );
}
