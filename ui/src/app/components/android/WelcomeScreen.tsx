import { Server, ArrowRight } from "lucide-react";
import { Button } from "../ui/button";

interface WelcomeScreenProps {
  onStart: () => void;
  onSkip: () => void;
}

export function WelcomeScreen({ onStart, onSkip }: WelcomeScreenProps) {
  return (
    <div className="min-h-screen bg-[#0B1220] flex flex-col items-center justify-center p-6 text-center overflow-hidden">
      <div className="absolute top-6 right-6 z-50">
        <Button 
          variant="ghost" 
          onClick={onSkip}
          className="text-[#9CA3AF] hover:text-white"
        >
          Skip Tutorial
        </Button>
      </div>

      <div className="mb-12 relative z-10">
        <div className="w-24 h-24 bg-[#2563EB] rounded-[2rem] flex items-center justify-center shadow-xl mb-8 mx-auto">
          <Server className="w-12 h-12 text-white" />
        </div>
        
        <h1 className="text-3xl font-extrabold text-[#FFFFFF] mb-4 tracking-tight">
          Welcome to <br />
          <span className="text-[#3B82F6]">
            Easy Storage Cloud
          </span>
        </h1>
        
        <p className="text-lg text-[#9CA3AF] max-w-xs mx-auto leading-relaxed">
          Turn your external storage into a personal cloud that you can access anytime, anywhere.
        </p>
      </div>

      <div className="w-full max-w-xs space-y-4 relative z-10">
        <Button 
          onClick={onStart}
          className="w-full h-14 bg-[#2563EB] hover:bg-[#1d4ed8] text-white rounded-2xl text-lg font-bold flex items-center justify-center gap-2"
        >
          Get Started
          <ArrowRight className="w-5 h-5" />
        </Button>
      </div>

      {/* Simplified background elements without complex blur/opacity that may fail */}
      <div className="absolute top-0 left-0 w-full h-full bg-[#111827] -z-10" />
    </div>
  );
}
