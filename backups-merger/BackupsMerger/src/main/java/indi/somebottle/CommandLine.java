package indi.somebottle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * 命令行参数解析和非交互式合并流程
 */
public final class CommandLine {
    private CommandLine() {
    }

    /**
     * 命令行参数。{@code mergeUpTo} 使用 {@code -l} 输出中的序号（从 1 开始）
     */
    static final class CommandLineOptions {
        private File groupDir;
        private boolean list;
        private int mergeUpTo = -1;
        private File outputFile;
        private boolean help;
    }

    /**
     * 解析命令行参数
     *
     * @return 解析后的参数；没有参数时返回 {@code null}，稍后进入交互模式
     */
    static CommandLineOptions parseCommandLine(String[] args) {
        if (args == null || args.length == 0)
            return null;

        CommandLineOptions options = new CommandLineOptions();
        String groupDirPath = null;
        String outputFilePath = null;
        boolean mergeUpToSpecified = false;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            String option = argument;
            String inlineValue = null;
            if (argument.startsWith("--")) {
                // 处理 --merge-up-to=233 这样的带等号情况
                int equals = argument.indexOf('=');
                if (equals > 2) {
                    option = argument.substring(0, equals);
                    inlineValue = argument.substring(equals + 1);
                }
            }

            switch (option) {
                case "-h", "--help" -> options.help = true;
                case "-l", "--list" -> {
                    if (inlineValue != null)
                        throw new IllegalArgumentException(option + " does not take a value");
                    options.list = true;
                }
                case "-d", "--group-dir" -> {
                    if (groupDirPath != null)
                        throw new IllegalArgumentException("The group directory was specified more than once");
                    if (inlineValue == null) {
                        if (i + 1 >= args.length)
                            throw new IllegalArgumentException(option + " requires a value");
                        // 处理 --group-dir xxx 这种空格分隔风格的参数
                        groupDirPath = args[++i];
                    } else {
                        groupDirPath = inlineValue;
                    }
                }
                case "-t", "--merge-up-to" -> {
                    if (mergeUpToSpecified)
                        throw new IllegalArgumentException("The merge target was specified more than once");
                    mergeUpToSpecified = true;
                    String value;
                    if (inlineValue == null) {
                        if (i + 1 >= args.length)
                            throw new IllegalArgumentException(option + " requires a value");
                        // 处理 --merge-up-to 2 这种空格分隔风格的参数
                        value = args[++i];
                    } else {
                        value = inlineValue;
                    }
                    try {
                        options.mergeUpTo = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(option + " requires a positive integer", e);
                    }
                    if (options.mergeUpTo <= 0)
                        throw new IllegalArgumentException(option + " requires a positive integer");
                }
                case "-o", "--output-file" -> {
                    if (outputFilePath != null)
                        throw new IllegalArgumentException("The output file was specified more than once");
                    if (inlineValue == null) {
                        if (i + 1 >= args.length)
                            throw new IllegalArgumentException(option + " requires a value");
                        outputFilePath = args[++i];
                    } else {
                        outputFilePath = inlineValue;
                    }
                }
                default -> throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }

        if (options.help)
            return options;
        if (groupDirPath != null && groupDirPath.isBlank())
            throw new IllegalArgumentException("-d/--group-dir requires a directory");
        if (outputFilePath != null && outputFilePath.isBlank())
            throw new IllegalArgumentException("-o/--output-file requires a file path");
        if (groupDirPath == null)
            throw new IllegalArgumentException("-d/--group-dir is required when using command-line mode");
        if (options.list && mergeUpToSpecified)
            throw new IllegalArgumentException("-l/--list and -t/--merge-up-to cannot be used together");
        if (outputFilePath != null && !mergeUpToSpecified)
            throw new IllegalArgumentException("-o/--output-file can only be used with -t/--merge-up-to");
        if (!options.list && !mergeUpToSpecified)
            throw new IllegalArgumentException("Use either -l/--list or -t/--merge-up-to");

        options.groupDir = new File(groupDirPath).getAbsoluteFile();
        if (outputFilePath != null)
            options.outputFile = new File(outputFilePath).getAbsoluteFile();
        return options;
    }

    private static void printCommandLineUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar BackupsMerger.jar -d <group-dir> -l");
        System.out.println("  java -jar BackupsMerger.jar -d <group-dir> -t <number> [-o <output-file>]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -d, --group-dir <dir>       Backup group directory (required in CLI mode)");
        System.out.println("  -l, --list                  List available incremental backups");
        System.out.println("  -t, --merge-up-to <number>  Merge through the listed incremental number");
        System.out.println("  -o, --output-file <file>    Output zip path (default: ./merged.zip)");
        System.out.println("  -h, --help                 Show this help");
    }

    /**
     * 执行非交互式命令行模式
     *
     * @return 进程退出码（0 表示成功，2 表示参数错误，1 表示备份处理失败）
     */
    static int runCommandLine(String[] args) {
        final CommandLineOptions options;
        try {
            options = parseCommandLine(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printCommandLineUsage();
            return 2;
        }

        if (options == null)
            return 0;
        if (options.help) {
            printCommandLineUsage();
            return 0;
        }

        File groupDir = options.groupDir;
        if (!groupDir.isDirectory()) {
            System.err.println("Error: group directory does not exist or is not a directory: "
                    + groupDir.getAbsolutePath());
            return 1;
        }

        BackupRecord backupRecord;
        try {
            backupRecord = Task.readBackupRecord(groupDir);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }

        List<BackupRecord.IncreBackupHistoryItem> declared = backupRecord.getIncreBackupsHistory();
        if (!Task.validateIncrementalFiles(groupDir, declared == null ? List.of() : declared))
            return 1;
        List<BackupRecord.IncreBackupHistoryItem> increHistory =
                Task.discoverAvailableIncrementals(groupDir, declared);

        if (options.list) {
            // 仅列表
            Task.printIncrementals(increHistory);
            return 0;
        }

        File fullBackupFile = new File(groupDir, "full.zip");
        if (!fullBackupFile.isFile()) {
            System.err.println("Error: no full.zip found in " + groupDir.getAbsolutePath());
            return 1;
        }
        if (options.mergeUpTo > increHistory.size()) {
            System.err.println(increHistory.isEmpty()
                    ? "Error: no incremental backup exists to merge"
                    : "Error: merge-up-to must be between 1 and " + increHistory.size());
            return 2;
        }

        // 默认输出到当前目录下的 merged.zip，如果指定了输出文件则使用指定路径
        File outputFile = options.outputFile == null
                ? new File(System.getProperty("user.dir"), "merged.zip")
                : options.outputFile;
        outputFile = outputFile.getAbsoluteFile();
        int outputValidation = Task.validateOutputFile(outputFile, fullBackupFile, groupDir, increHistory);
        if (outputValidation != 0)
            return outputValidation;

        return mergeFromCommandLine(groupDir, fullBackupFile, increHistory,
                options.mergeUpTo, outputFile);
    }

    private static int mergeFromCommandLine(File groupDir, File fullBackupFile,
                                            List<BackupRecord.IncreBackupHistoryItem> increHistory,
                                            int mergeUpTo, File outputFile) {
        File tmpDir;
        try {
            tmpDir = Files.createTempDirectory("potato-sack-merger-").toFile();
        } catch (IOException e) {
            System.err.println("Error: failed to create temp directory: " + e.getMessage());
            return 1;
        }

        try {
            return Task.mergeBackups(groupDir, fullBackupFile, increHistory,
                    mergeUpTo, outputFile, tmpDir) ? 0 : 1;
        } finally {
            if (!Utils.rmDir(tmpDir))
                System.err.println("Warning: failed to delete temp directory " + tmpDir.getAbsolutePath());
        }
    }

}
