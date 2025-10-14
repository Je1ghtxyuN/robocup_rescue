package SEU.module.complex.dcop;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import rescuecore2.worldmodel.EntityID;

public class DebugLogger {
    private static final String LOG_DIR = "logs";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    // 为每个模块创建单独的日志文件
    private static final Map<String, String> MODULE_FILES = Map.of(
        "救护车分配器", "ambulance_allocator_log.txt",
        "消防分配器", "fire_allocator_log.txt", 
        "警察分配器", "police_allocator_log.txt",
        "高速公路聚类", "highways_clustering_log.txt"
    );
    
    static {
        try {
            Path dirPath = Paths.get(LOG_DIR);
            Files.createDirectories(dirPath);
            System.out.println("【DebugLogger】初始化 - 日志目录: " + dirPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("【DebugLogger】初始化失败: " + e.getMessage());
        }
    }
    
    public static void log(String module, String message) {
        // 控制台输出（便于实时调试）
        System.out.println("【DEBUG】" + module + ": " + message);
        
        // 文件输出（简化格式）
        writeToModuleFile(module, message);
    }
    
    private static void writeToModuleFile(String module, String message) {
        String filename = MODULE_FILES.getOrDefault(module, "general_log.txt");
        String filePath = LOG_DIR + File.separator + filename;
        
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, true))) {
                String time = SDF.format(new Date());
                
                // 简化输出格式：时间 | 模块 | 消息
                writer.println(time + " | " + module + " | " + message);
                writer.flush();
            }
            
        } catch (IOException e) {
            System.err.println("【DebugLogger】写入文件失败 (" + filePath + "): " + e.getMessage());
        }
    }
    
    /**
     * 记录分配器详细结果（保持详细格式）
     */
    public static void logAllocationResult(String module, Map<EntityID, EntityID> result, 
                                         Set<EntityID> agents, Set<EntityID> tasks) {
        String filename = MODULE_FILES.getOrDefault(module, "allocation_log.txt");
        String filePath = LOG_DIR + File.separator + filename;
        
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, true))) {
                String time = SDF.format(new Date());
                
                writer.println("=== " + module + " 分配结果详细报告 ===");
                writer.println("生成时间: " + time);
                writer.println("智能体总数: " + agents.size());
                writer.println("任务总数: " + tasks.size());
                writer.println("实际分配数: " + result.values().stream()
                    .filter(Objects::nonNull).count());
                writer.println();
                
                // 分配详情
                writer.println("分配详情:");
                int assignedCount = 0;
                for (Map.Entry<EntityID, EntityID> entry : result.entrySet()) {
                    String taskInfo = (entry.getValue() == null) ? "搜索任务" : 
                                     "任务ID: " + entry.getValue().getValue();
                    writer.println("  智能体 " + entry.getKey().getValue() + " -> " + taskInfo);
                    if (entry.getValue() != null) assignedCount++;
                }
                writer.println("已分配任务智能体: " + assignedCount + " / " + agents.size());
                writer.println();
                
                // 任务统计
                writer.println("任务统计:");
                Map<EntityID, Long> taskAssignmentCount = result.values().stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(task -> task, Collectors.counting()));
                
                for (Map.Entry<EntityID, Long> entry : taskAssignmentCount.entrySet()) {
                    writer.println("  任务 " + entry.getKey().getValue() + " 被分配次数: " + entry.getValue());
                }
                writer.println("搜索任务分配数: " + result.values().stream()
                    .filter(Objects::isNull).count());
                
                writer.println("=== 报告结束 ===");
                writer.println();
                
            }
            
            System.out.println(module + "详细报告已输出到: " + filePath);
            
        } catch (IOException e) {
            System.err.println("写入分配报告失败: " + e.getMessage());
        }
    }
    
    /**
     * 记录性能指标（保持详细格式）
     */
    public static void logPerformanceMetrics(String module, String metricName, 
                                           Map<String, Object> metrics) {
        String filename = MODULE_FILES.getOrDefault(module, "performance_log.txt");
        String filePath = LOG_DIR + File.separator + filename;
        
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, true))) {
                String time = SDF.format(new Date());
                
                writer.println("=== " + module + " 性能指标 ===");
                writer.println("记录时间: " + time);
                writer.println("指标名称: " + metricName);
                writer.println("指标详情:");
                
                for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                    writer.println("  " + entry.getKey() + ": " + entry.getValue());
                }
                
                writer.println("=== 指标记录结束 ===");
                writer.println();
                
            }
            
        } catch (IOException e) {
            System.err.println("写入性能指标失败: " + e.getMessage());
        }
    }
    
    public static String getLogDirectory() {
        return Paths.get(LOG_DIR).toAbsolutePath().toString();
    }
    
    public static String getModuleLogPath(String module) {
        String filename = MODULE_FILES.getOrDefault(module, "general_log.txt");
        return Paths.get(LOG_DIR, filename).toAbsolutePath().toString();
    }
}