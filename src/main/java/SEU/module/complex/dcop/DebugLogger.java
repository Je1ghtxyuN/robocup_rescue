package SEU.module.complex.dcop;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DebugLogger {
    // 1. 相对路径：在工作目录下建立 logs/rescue_log.txt
    private static final String LOG_DIR  = "logs";
    private static final String LOG_FILE = LOG_DIR + File.separator + "rescue_log.txt";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // 2. 类加载时就创建目录，保证一定存在
    static {
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
        } catch (IOException e) {
            System.err.println("【DebugLogger】创建日志目录失败: " + e.getMessage());
        }
    }

    // 3. 写日志
    public static void log(String module, String message) {
        // 如果还担心目录被删，每次写之前再保险创建一次
        try {
            Files.createDirectories(Paths.get(LOG_DIR));
        } catch (IOException e) {
            System.err.println("【DebugLogger】再次创建日志目录失败: " + e.getMessage());
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            String time = SDF.format(new Date());
            writer.write("[" + time + "] [" + module + "] " + message);
            writer.newLine();
            writer.flush();          // 立即刷盘，防止调试时看不到
        } catch (IOException e) {
            System.err.println("【DebugLogger】写日志失败: " + e.getMessage());
        }
    }

    // 4. 可选：返回绝对路径，方便在控制台打印
    public static String getAbsoluteLogPath() {
        return Paths.get(LOG_FILE).toAbsolutePath().toString();
    }
}