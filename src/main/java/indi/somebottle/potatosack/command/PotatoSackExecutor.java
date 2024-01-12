package indi.somebottle.potatosack.command;

import indi.somebottle.potatosack.utils.Config;
import indi.somebottle.potatosack.utils.ConsoleSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PotatoSackExecutor implements CommandExecutor {
    private final Config config;

    public PotatoSackExecutor(Config config) {
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            switch (args[0]) {
                case "reload":
                    if ((sender instanceof Player) && !sender.hasPermission("potatosack.reload")) {
                        ConsoleSender.autoSend(sender, "You don't have permission to execute this command!");
                        return false;
                    }
                    if (config.reload()) {
                        ConsoleSender.autoSend(sender, "Successfully reloaded!");
                    } else {
                        ConsoleSender.autoSend(sender, "Failed to reload!");
                    }
                    return true;
            }
        }
        ConsoleSender.autoSend(sender, "Usage: /potatosack reload");
        return false;
    }
}
