package ru.javaroot.smpshop.plat;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class Exec {
    private final Plugin plug;
    private final Set<ScheduledTask> tasks = ConcurrentHashMap.newKeySet();

    public Exec(Plugin plug) {
        this.plug = plug;
    }

    public Result global(Runnable task) {
        if (!plug.isEnabled()) {
            return Result.REJECTED;
        }
        try {
            plug.getServer().getGlobalRegionScheduler().execute(plug, task);
            return Result.SCHEDULED;
        } catch (RejectedExecutionException | IllegalStateException ex) {
            return Result.REJECTED;
        }
    }

    public Result region(Location loc, Runnable task) {
        if (!plug.isEnabled()) {
            return Result.REJECTED;
        }
        try {
            plug.getServer().getRegionScheduler().execute(plug, loc, task);
            return Result.SCHEDULED;
        } catch (RejectedExecutionException | IllegalStateException ex) {
            return Result.REJECTED;
        }
    }

    public Result entity(Entity ent, Runnable task) {
        return entity(ent, task, null);
    }

    public Result entity(Entity ent, Runnable task, Runnable retired) {
        if (!plug.isEnabled()) {
            runIfPresent(retired);
            return Result.REJECTED;
        }
        try {
            if (ent.getScheduler().execute(plug, task, retired, 0L)) {
                return Result.SCHEDULED;
            }
            runIfPresent(retired);
            return Result.RETIRED;
        } catch (RejectedExecutionException | IllegalStateException ex) {
            runIfPresent(retired);
            return Result.REJECTED;
        }
    }

    public Result sender(CommandSender snd, Runnable task) {
        if (snd instanceof Entity ent) {
            return entity(ent, task);
        }
        return global(task);
    }

    public Result async(Runnable task) {
        if (!plug.isEnabled()) {
            return Result.REJECTED;
        }
        try {
            plug.getServer().getAsyncScheduler().runNow(plug, scheduled -> task.run());
            return Result.SCHEDULED;
        } catch (RejectedExecutionException | IllegalStateException ex) {
            return Result.REJECTED;
        }
    }

    public ScheduledTask asyncRepeating(long delay, long period, TimeUnit unit, Consumer<ScheduledTask> task) {
        if (!plug.isEnabled()) {
            return null;
        }
        try {
            ScheduledTask handle = plug.getServer().getAsyncScheduler().runAtFixedRate(plug, scheduled -> {
                if (!plug.isEnabled()) {
                    scheduled.cancel();
                    tasks.remove(scheduled);
                    return;
                }
                task.accept(scheduled);
            }, delay, period, unit);
            tasks.add(handle);
            return handle;
        } catch (RejectedExecutionException | IllegalStateException ex) {
            return null;
        }
    }

    public void cancel(ScheduledTask handle) {
        if (handle != null) {
            tasks.remove(handle);
            handle.cancel();
        }
    }

    public void cancelAll() {
        for (ScheduledTask handle : tasks) {
            handle.cancel();
        }
        tasks.clear();
    }

    public boolean ownsRegion(Location loc) {
        return Bukkit.isOwnedByCurrentRegion(loc);
    }

    public boolean ownsEntity(Entity ent) {
        return Bukkit.isOwnedByCurrentRegion(ent);
    }

    private static void runIfPresent(Runnable task) {
        if (task != null) {
            task.run();
        }
    }

    public enum Result {
        SCHEDULED,
        RETIRED,
        REJECTED
    }
}
