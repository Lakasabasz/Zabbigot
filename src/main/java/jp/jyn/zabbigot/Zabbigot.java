package jp.jyn.zabbigot;

import jp.jyn.zabbigot.command.SubExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Zabbigot extends JavaPlugin {
    private final Deque<Runnable> destructor = new ArrayDeque<>();
    private final ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor();

    private StatusManager manager;

    @Override
    public void onEnable() {
        destructor.clear();

        MainConfig config = new MainConfig(this);

        // start TPS watcher
        TpsWatcher watcher = new TpsWatcher();
        BukkitTask task = getServer().getScheduler().runTaskTimer(this, watcher, 0, 1);
        destructor.addFirst(task::cancel);

        // start Event count
        EventCounter event = new EventCounter();
        getServer().getPluginManager().registerEvents(event, this);
        destructor.addFirst(() -> HandlerList.unregisterAll(this));

        // StatusManager
        manager = new StatusManager.Builder(config.sender)
            .setHost(config.hostname)
            .setKeyConverter(config.keyConverter)
            .addDisabledKeys(config.disable)
            .addDefaultStatus(watcher, event)
            .build();

        // status sender
        if (config.interval > 0) {
            ScheduledFuture<?> future = pool.scheduleAtFixedRate(
                manager::send,
                config.interval,
                config.interval,
                TimeUnit.SECONDS
            );
            destructor.addFirst(() -> future.cancel(false));
        }

        // command
        SubExecutor executor = new SubExecutor(this, watcher, event);
        PluginCommand command = getCommand("zabbigot");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        destructor.addFirst(() -> command.setExecutor(this));
        destructor.addFirst(() -> command.setTabCompleter(this));
    }

    @Override
    public void onDisable() {
        while (!destructor.isEmpty()) {
            destructor.removeFirst().run();
        }
    }

    /**
     * Get status manager
     *
     * @return StatusManager
     */
    public StatusManager getManager() {
        return manager;
    }
}
