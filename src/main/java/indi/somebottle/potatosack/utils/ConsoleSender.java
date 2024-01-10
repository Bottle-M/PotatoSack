package indi.somebottle.potatosack.utils;

import indi.somebottle.potatosack.PotatoSack;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ConsoleSender {
    /**
     * 发送消息到控制台
     *
     * @param text 待发送的消息内容
     */
    public static void toConsole(String text) {
        // 通过CommandSender对象的方法发送信息到控制台
        Bukkit.getScheduler().runTask(PotatoSack.plugin, () -> {
            // 放到主线程中执行
            CommandSender sender = Bukkit.getConsoleSender();
            sender.sendMessage(ChatColor.GOLD + "[" + Constants.PLUGIN_PREFIX + "] " + ChatColor.RESET + text);
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
            target.sendMessage(ChatColor.GOLD + "[" + Constants.PLUGIN_PREFIX + "] " + ChatColor.RESET + text);
        });
    }
}