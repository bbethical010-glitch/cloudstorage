import { motion } from "motion/react";
import { Clock, CheckCircle2, Link2, FolderPlus, FileUp, ChevronLeft, Activity as ActivityIcon, FolderOpen } from "lucide-react";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { useNavigate, Link } from "react-router";

export function AndroidActivity() {
  const navigate = useNavigate();

  const activities = [
    {
      id: 1,
      type: "upload",
      title: "File uploaded",
      target: "movie.mp4",
      time: "10:32 AM",
      icon: <FileUp className="w-4 h-4 text-[#2563EB]" />,
    },
    {
      id: 2,
      type: "share",
      title: "Share link created",
      target: "Vacation Photos",
      time: "10:15 AM",
      icon: <Link2 className="w-4 h-4 text-[#A855F7]" />,
    },
    {
      id: 3,
      type: "folder",
      title: "Folder created",
      target: "Photos",
      time: "09:58 AM",
      icon: <FolderPlus className="w-4 h-4 text-[#22C55E]" />,
    },
    {
      id: 4,
      type: "system",
      title: "Storage connected",
      target: "SanDisk USB",
      time: "09:45 AM",
      icon: <CheckCircle2 className="w-4 h-4 text-[#2563EB]" />,
    }
  ];

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
        <h1 className="text-xl font-bold">Recent Activity</h1>
      </div>

      <div className="space-y-4">
        {activities.map((item, i) => (
          <motion.div
            key={item.id}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.05 }}
          >
            <Card className="bg-[#111827] border-[#374151] p-4 flex items-center gap-4">
              <div className="w-10 h-10 rounded-xl bg-[#1F2937] flex items-center justify-center shrink-0">
                {item.icon}
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-sm font-bold leading-none">{item.title}</p>
                <p className="text-xs text-[#9CA3AF] mt-1 truncate">{item.target}</p>
              </div>
              <div className="text-right">
                <div className="flex items-center gap-1 text-[10px] text-[#9CA3AF] font-mono">
                  <Clock className="w-3 h-3" />
                  {item.time}
                </div>
              </div>
            </Card>
          </motion.div>
        ))}
      </div>
      {/* Bottom Nav */}
      <div className="fixed bottom-6 left-6 right-6 h-20 bg-[#111827]/80 backdrop-blur-xl border border-[#374151] rounded-[2.5rem] flex items-center justify-around shadow-2xl z-50">
        <Link to="/" className="p-4 text-[#9CA3AF] hover:text-[#E5E7EB] flex flex-col items-center gap-1">
          <ActivityIcon className="w-6 h-6" />
        </Link>
        <Link to="/browser" className="p-4 text-[#9CA3AF] hover:text-[#E5E7EB] flex flex-col items-center gap-1">
          <FolderOpen className="w-6 h-6" />
        </Link>
        <Link to="/activity" className="p-4 text-[#2563EB] flex flex-col items-center gap-1">
          <Clock className="w-6 h-6" />
        </Link>
      </div>
    </div>
  );
}
