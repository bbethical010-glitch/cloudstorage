import { useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import { Globe, Usb, PlayCircle, Share2, ChevronRight } from "lucide-react";
import { Button } from "../ui/button";

const onboardingSteps = [
  {
    title: "Connect Storage",
    description: "Plug in your USB drive, SD card, or select an internal folder to host.",
    icon: Usb,
    color: "text-[#2563EB]",
    bgColor: "bg-blue-500/10",
  },
  {
    title: "Start Node",
    description: "Turn your device into a secure personal cloud server with one tap.",
    icon: PlayCircle,
    color: "text-[#10B981]",
    bgColor: "bg-green-500/10",
  },
  {
    title: "Remote Access",
    description: "Access your files securely from any web browser or device.",
    icon: Globe,
    color: "text-[#A855F7]",
    bgColor: "bg-purple-500/10",
  },
  {
    title: "Share Files",
    description: "Generate precise, secure links to share photos and documents with friends.",
    icon: Share2,
    color: "text-[#F59E0B]",
    bgColor: "bg-orange-500/10",
  }
];

interface AndroidOnboardingProps {
  onComplete?: () => void;
}

export function AndroidOnboarding({ onComplete }: AndroidOnboardingProps) {
  const [currentStep, setCurrentStep] = useState(0);

  const handleComplete = () => {
    localStorage.setItem("hasSeenTutorial", "true");
    if (onComplete) {
      onComplete();
    } else {
      window.location.hash = window.Android ? "#/" : "#/console";
    }
  };

  const handleNext = () => {
    if (currentStep < onboardingSteps.length - 1) {
      setCurrentStep(currentStep + 1);
    } else {
      handleComplete();
    }
  };

  const handleSkip = () => {
    handleComplete();
  };

  const step = onboardingSteps[currentStep];
  const Icon = step.icon;

  return (
    <div className="min-h-screen bg-[#0B1220] flex flex-col items-center justify-between p-6">
      <div className="w-full flex justify-end">
        <Button 
          variant="ghost" 
          onClick={handleSkip}
          className="text-[#9CA3AF] font-mono text-xs tracking-widest"
        >
          SKIP
        </Button>
      </div>

      <div className="flex-1 flex flex-col items-center justify-center w-full max-w-sm">
        <AnimatePresence mode="wait">
          <motion.div
            key={currentStep}
            initial={{ opacity: 0, x: 50 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -50 }}
            className="w-full text-center"
          >
            <div className={`inline-flex p-8 rounded-[32px] ${step.bgColor} mb-12 relative`}>
              <Icon className={`w-16 h-16 ${step.color}`} />
              <motion.div 
                className="absolute inset-0 rounded-[32px] bg-white/5"
                animate={{ scale: [1, 1.1, 1], opacity: [0, 0.5, 0] }}
                transition={{ duration: 3, repeat: Infinity }}
              />
            </div>

            <h2 className="text-2xl font-bold text-[#E5E7EB] mb-4">
              {step.title}
            </h2>
            <p className="text-[#9CA3AF] text-sm leading-relaxed px-4">
              {step.description}
            </p>
          </motion.div>
        </AnimatePresence>
      </div>

      <div className="w-full max-w-sm flex flex-col items-center gap-8 pb-8">
        {/* Progress Dots */}
        <div className="flex gap-2">
          {onboardingSteps.map((_, i) => (
            <div 
              key={i}
              className={`h-1.5 rounded-full transition-all duration-300 ${
                i === currentStep ? "w-8 bg-[#2563EB]" : "w-1.5 bg-[#1F2937]"
              }`}
            />
          ))}
        </div>

        <Button 
          onClick={handleNext}
          className="w-full h-14 bg-[#2563EB] hover:bg-[#2563EB]/90 rounded-2xl text-lg font-bold shadow-xl shadow-blue-900/20"
        >
          {currentStep === onboardingSteps.length - 1 ? "Finish" : "Continue"}
        </Button>
      </div>
    </div>
  );
}
