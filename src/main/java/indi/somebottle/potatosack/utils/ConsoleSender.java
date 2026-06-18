package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

public final class ConsoleSender {
    /**
     * 记录插件出错信息（方便追溯）
     *
     * @param msg 错误信息字符串
     * @apiNote 本方法会将错误信息记入服务端日志；插件实例不可用时使用服务器日志，最后兜底到标准错误流
     */
    public static void logError(String msg) {
        log(Level.SEVERE, "Fatal: " + msg);
    }

    /**
     * 记录插件警告信息（方便追溯）
     *
     * @param msg 警告信息字符串
     * @apiNote 本方法会将警告信息记入服务端日志；插件实例不可用时使用服务器日志，最后兜底到标准错误流
     */
    public static void logWarn(String msg) {
        log(Level.WARNING, "Warning: " + msg);
    }

    /**
     * 记录插件一般信息
     *
     * @param msg 信息字符串
     * @apiNote 本方法会将插件信息记入服务端日志；插件实例不可用时使用服务器日志，最后兜底到标准错误流
     */
    public static void logInfo(String msg) {
        log(Level.INFO, "Info: " + msg);
    }

    /**
     * 记录插件调试信息（方便追溯），仅在配置中 verbose-logging 设置为 true 时输出
     *
     * @param msg 调试信息字符串
     * @apiNote 本方法会将调试信息记入服务端日志；插件实例不可用时使用服务器日志，最后兜底到标准错误流
     */
    public static void logDebug(String msg) {
        log(Level.FINE, "Debug: " + msg);
    }

    private static void log(Level level, String msg) {
        Plugin plugin = PotatoSack.getPluginInstance();
        if (plugin != null) {
            plugin.getLogger().log(level, msg);
            return;
        }
        try {
            Bukkit.getLogger().log(level, "[" + Constants.PLUGIN_PREFIX + "] " + msg);
        } catch (Throwable ignored) {
            System.err.println("[" + Constants.PLUGIN_PREFIX + "] " + level.getName() + ": " + msg);
        }
    }

    /**
     * 发送消息到控制台（仅限主线程）
     *
     * @param text 待发送的消息内容
     */
    public static void toConsoleSync(String text) {
        // 通过CommandSender对象的方法发送信息到控制台
        CommandSender sender = Bukkit.getConsoleSender();
        sender.sendMessage(text);
    }

    /**
     * 通过调度消息到主线程实现发送消息到控制台（可在异步方法中调用）
     *
     * @param text 待发送的消息内容
     * @apiNote 请勿在插件disable后调用此方法。如果主线程阻塞，该方法发送的消息可能会延迟输出到控制台
     */
    public static void toConsole(String text) {
        // 通过CommandSender对象的方法发送信息到控制台
        System.out.println(text);
    }

    /**
     * 发送消息到特定玩家
     *
     * @param target Player对象
     * @param text   待发送的消息内容
     */
    public static void toPlayer(Player target, String text) {
        // 放在主线程中执行
        Plugin plugin = PotatoSack.getPluginInstance();
        if (plugin == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(ChatColor.GOLD + "[" + Constants.PLUGIN_PREFIX + "]" + ChatColor.RESET + " " + text));
    }

    /**
     * 自动判断是玩家还是控制台，进而发送消息
     *
     * @param sender 发送者对象
     * @param text   待发送的消息内容
     */
    public static void autoSend(Object sender, String text) {
        if (sender instanceof Player) {
            toPlayer((Player) sender, text);
        } else {
            toConsole(text);
        }
    }
}
