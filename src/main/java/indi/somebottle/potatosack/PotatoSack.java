package indi.somebottle.potatosack;

import indi.somebottle.potatosack.utils.ConsoleSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class PotatoSack extends JavaPlugin {
    public static Plugin plugin = null;
    private final ConsoleSender sender = new ConsoleSender();

    @Override
    public void onEnable() {
        plugin = this; // 暴露插件对象
        // 开始初始化插件
        sender.toConsole("Potato Sack Initializing...");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
