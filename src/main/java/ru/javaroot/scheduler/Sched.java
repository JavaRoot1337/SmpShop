package ru.javaroot.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Sched {
    private final Plugin plugin;

    public Sched(Plugin plugin) {
        this.plugin = plugin;
    }

    public void region(Location location, Runnable task) {
        plugin.getServer().getRegionScheduler().execute(plugin, location, task);
    }

    public void entity(Entity entity, Runnable task) {
        entity(entity, task, null);
    }

    public void entity(Entity entity, Runnable task, Runnable retired) {
        entity.getScheduler().execute(plugin, task, retired, 0L);
    }

    public void async(Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    public ScheduledTask asyncRepeating(long initialDelay, long period, TimeUnit unit, Consumer<ScheduledTask> task) {
        return plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task, initialDelay, period, unit);
    }
}
