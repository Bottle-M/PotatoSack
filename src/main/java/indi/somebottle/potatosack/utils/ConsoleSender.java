package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ConsoleSender {
    // TODO: 这个模块可以只保留 toPlayer, 输出到控制台直接用 System.out.print
    /**
     * 发送消息到控制台（仅限主线程）
     *
     * @param text 待发送的消息内容
     */
    public static void toConsoleSync(String text) {
        // 通过CommandSender对象的方法发送信息到控制台
        String msg = ChatColor.GOLD + "[" + Constants.PLUGIN_PREFIX + "] " + ChatColor.RESET + " " + text;
        CommandSender sender = Bukkit.getConsoleSender();
        sender.sendMessage(msg);
    }

    /**
     * 发送消息到控制台（可在异步方法中调用）
     *
     * @param text 待发送的消息内容
     * @apiNote 请勿在插件disable后调用此方法，可以调用toConsoleSync
     */
    public static void toConsole(String text) {
        // 通过CommandSender对象的方法发送信息到控制台
        String msg = ChatColor.GOLD + "[" + Constants.PLUGIN_PREFIX + "]" + ChatColor.RESET + " " + text;
        Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
            // 放到主线程中执行
            PotatoSack.plugin.getLogger().info(msg);
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