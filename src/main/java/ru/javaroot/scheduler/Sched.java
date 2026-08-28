package ru.javaroot.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public class Sched {
    private final Plugin plugin;

    public Sched(Plugin plugin) {
        this.plugin = plugin;
    }

    public ScheduleResult global(Runnable task) {
        if (!plugin.isEnabled()) {
            return ScheduleResult.REJECTED;
        }
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
            return ScheduleResult.SCHEDULED;
        } catch (RejectedExecutionException | IllegalStateException exception) {
            return ScheduleResult.REJECTED;
        }
    }

    public ScheduleResult region(Location location, Runnable task) {
        if (!plugin.isEnabled()) {
            return ScheduleResult.REJECTED;
        }
        try {
            plugin.getServer().getRegionScheduler().execute(plugin, location, task);
            return ScheduleResult.SCHEDULED;
        } catch (RejectedExecutionException | IllegalStateException exception) {
            return ScheduleResult.REJECTED;
        }
    }

    public ScheduleResult entity(Entity entity, Runnable task) {
        return entity(entity, task, null);
    }

    public ScheduleResult entity(Entity entity, Runnable task, Runnable retired) {
        if (!plugin.isEnabled()) {
            if (retired != null) {
                retired.run();
            }
            return ScheduleResult.REJECTED;
        }
        try {
            if (entity.getScheduler().execute(plugin, task, retired, 0L)) {
                return ScheduleResult.SCHEDULED;
            }
            if (retired != null) {
                retired.run();
            }
            return ScheduleResult.RETIRED;
        } catch (RejectedExecutionException | IllegalStateException exception) {
            if (retired != null) {
                retired.run();
            }
            return ScheduleResult.REJECTED;
        }
    }

    public ScheduleResult async(Runnable task) {
        if (!plugin.isEnabled()) {
            return ScheduleResult.REJECTED;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
            return ScheduleResult.SCHEDULED;
        } catch (RejectedExecutionException | IllegalStateException exception) {
            return ScheduleResult.REJECTED;
        }
    }

    public ScheduledTask asyncRepeating(long initialDelay, long period, TimeUnit unit, Consumer<ScheduledTask> task) {
        return plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task, initialDelay, period, unit);
    }

    public enum ScheduleResult {
        SCHEDULED,
        RETIRED,
        REJECTED
    }
}
