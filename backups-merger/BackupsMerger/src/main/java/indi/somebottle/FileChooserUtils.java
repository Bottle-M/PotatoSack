package indi.somebottle;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.InvocationTargetException;

/**
 * JFileChooser 相关工具
 *
 * <p>这个类集中负责 Swing EDT 调度、文件选择窗口创建/显示，以及发生异常时的必要诊断输出。
 * Main 只处理业务流程，不直接处理 Swing 线程细节。</p>
 */
public final class FileChooserUtils {
    private FileChooserUtils() {
        // 工具类不允许实例化。
    }

    /**
     * JFileChooser 的执行结果
     *
     * @param returnCode   JFileChooser.APPROVE_OPTION / CANCEL_OPTION / ERROR_OPTION
     * @param selectedFile 用户确认选择时的文件或目录；非 APPROVE 时通常为 null
     */
    public record Result(int returnCode, File selectedFile) {
    }

    /**
     * 显示“选择备份目录”窗口
     *
     * <p>JFileChooser 在 EDT 上创建和显示，并且直接使用 initialDirectory 作为初始目录。
     * 这里不使用隐藏 JFrame 作为 owner，避免 Windows（特别是 Git Bash/mintty 启动场景）
     * 首次初始化 AWT/Swing 时受到不可见 owner/focus 状态的干扰。</p>
     */
    public static Result showBackupDirectoryChooser(File initialDirectory) {
        final Result[] resultHolder = new Result[1];

        runOnSwingEdtAndWait("backup directory chooser", () -> {
            JFileChooser chooser = createChooser(initialDirectory);
            chooser.setDialogTitle("Choose a directory that contains a group of backups.");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setMultiSelectionEnabled(false);

            int result = chooser.showOpenDialog(null);
            File selectedFile = result == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile()
                    : null;
            resultHolder[0] = new Result(result, selectedFile);
        });

        if (resultHolder[0] == null) {
            throw new IllegalStateException("Backup directory chooser finished without a result.");
        }
        return resultHolder[0];
    }

    /**
     * 显示“保存合并备份”窗口
     *
     * @param suggestedOutputFile 建议的默认输出文件，例如 merged.zip
     */
    public static Result showSaveFileChooser(File suggestedOutputFile) {
        final Result[] resultHolder = new Result[1];

        runOnSwingEdtAndWait("save file chooser", () -> {
            File absoluteOutputFile = suggestedOutputFile == null
                    ? null
                    : suggestedOutputFile.getAbsoluteFile();
            File initialDirectory = absoluteOutputFile == null
                    ? null
                    : absoluteOutputFile.getParentFile();

            JFileChooser chooser = createChooser(initialDirectory);
            chooser.setDialogTitle("Save the merged backup as...");
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            chooser.setMultiSelectionEnabled(false);

            if (absoluteOutputFile != null) {
                chooser.setSelectedFile(absoluteOutputFile);
            }
            chooser.setFileFilter(new FileNameExtensionFilter("ZIP files", "zip"));

            int result = chooser.showSaveDialog(null);
            File selectedFile = result == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile()
                    : null;
            resultHolder[0] = new Result(result, selectedFile);
        });

        if (resultHolder[0] == null) {
            throw new IllegalStateException("Save file chooser finished without a result.");
        }
        return resultHolder[0];
    }

    /**
     * 将 JFileChooser 返回值转换成易读文本，方便日志定位问题
     */
    public static String describeResult(int result) {
        if (result == JFileChooser.APPROVE_OPTION) {
            return "APPROVE_OPTION (" + result + ")";
        }
        if (result == JFileChooser.CANCEL_OPTION) {
            return "CANCEL_OPTION (" + result + ")";
        }
        if (result == JFileChooser.ERROR_OPTION) {
            return "ERROR_OPTION (" + result + ")";
        }
        return "UNKNOWN (" + result + ")";
    }

    /**
     * JFileChooser 返回 ERROR_OPTION 或其它异常返回值时，输出必要诊断信息
     *
     * <p>只打印与 Java/AWT/Git Bash 排查相关的信息，不打印完整环境变量，避免日志过量。</p>
     */
    public static void printDiagnostics(String operation, File initialPath, int result) {
        System.err.println("[FileChooser] operation = " + operation);
        System.err.println("[FileChooser] result = " + describeResult(result));
        System.err.println("[FileChooser] initialPath = "
                + (initialPath == null ? "<null>" : initialPath.getAbsolutePath()));
        printRuntimeDiagnostics();
        System.err.flush();
    }

    /**
     * 打印与 Windows/Git Bash/AWT 问题有关的少量运行环境信息。
     * Main 在捕获未预期异常时也可以复用这个方法
     */
    public static void printRuntimeDiagnostics() {
        System.err.println("[Runtime] thread = " + Thread.currentThread().getName());
        System.err.println("[Runtime] os = " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " / " + System.getProperty("os.arch"));
        System.err.println("[Runtime] java = " + System.getProperty("java.vendor") + " "
                + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ")");
        System.err.println("[Runtime] user.dir = " + System.getProperty("user.dir"));
        System.err.println("[Runtime] java.awt.headless = " + GraphicsEnvironment.isHeadless());
        System.err.println("[Runtime] TERM = " + envOrUnset("TERM"));
        System.err.println("[Runtime] MSYSTEM = " + envOrUnset("MSYSTEM"));
        System.err.println("[Runtime] SHELL = " + envOrUnset("SHELL"));
    }

    /**
     * JFileChooser 的创建也统一放在 EDT 调用内部。
     * 直接指定初始目录，避免 Windows 首次启动时先加载默认目录、随后又立即切目录
     */
    private static JFileChooser createChooser(File initialDirectory) {
        if (initialDirectory == null) {
            return new JFileChooser();
        }
        return new JFileChooser(initialDirectory);
    }

    /**
     * Swing 大部分组件都不是线程安全的，因此 JFileChooser 的创建和 showXxxDialog 都统一放到 EDT
     *
     * <p>invokeAndWait 会让命令行主线程等待用户完成选择，但 Swing 的窗口事件仍由正确的
     * AWT-EventQueue 线程处理。</p>
     */
    private static void runOnSwingEdtAndWait(String operation, Runnable action) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "Cannot show " + operation + ": Java is running in headless mode.");
        }

        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for " + operation + ".", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(
                    "Failed to show " + operation + " on Swing EDT.", cause);
        }
    }

    private static String envOrUnset(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? "<unset>" : value;
    }
}
