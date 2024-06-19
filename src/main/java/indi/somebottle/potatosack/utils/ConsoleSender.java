package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ConsoleSender {
    /**
     * 记录插件出错信息（方便追溯）
     *
     * @param msg 错误信息字符串
     * @apiNote 本方法会将错误信息记入服务端日志，同时打印到控制台，本方法首先会在本线程打印到控制台一次，再在主线程打印一次
     */
    public static void logError(String msg) {
        String finalMsg = "Fatal: " + msg;
        System.out.println("[Println] " + finalMsg);
        if (PotatoSack.plugin != null) {
            // 记录到服务端日志
            // 因为logError可能在异步方法中被调用，这里需要把getLogger.severe通过runTask放回主线程调用
            Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
                PotatoSack.plugin.getLogger().severe("[Logger] " + finalMsg);
            });
        }
    }

    /**
     * 记录插件警告信息（方便追溯）
     *
     * @param msg 警告信息字符串
     * @apiNote 本方法会将警告信息记入服务端日志，同时打印到控制台, 本方法首先会在本线程打印到控制台一次，再在主线程打印一次
     */
    public static void logWarn(String msg) {
        String finalMsg = "Warning: " + msg;
        System.out.println("[Println] " + finalMsg);
        if (PotatoSack.plugin != null) {
            // 记录到服务端日志
            // 因为logWarn可能在异步方法中被调用，这里需要把getLogger.warning通过runTask放回主线程调用
            Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
                PotatoSack.plugin.getLogger().warning("[Logger] " + finalMsg);
            });
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
        // System.out.println(text);
        Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
            // 放到主线程中执行
            PotatoSack.plugin.getLogger().info(text);
        });
    }

    /**
     * 发送消息到特定玩家
     *
     * @param target Player对象
     * @param text   待发送的消息内容
     */
    public static void toPlayer(Player target, String text) {
        // 放在主线程中执行
        Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
            target.sendMessage(ChatColor.GOLD + "[" + Constants.PLUGIN_PREFIX + "]" + ChatColor.RESET + " " + text);
        });
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