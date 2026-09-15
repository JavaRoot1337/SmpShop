package ru.javaroot.logersxd;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Loggers {
    private final Channel lifecycle;
    private final Channel config;
    private final Channel trade;
    private final Channel shop;
    private final Channel gui;
    private final Channel integrations;
    private final Channel sound;
    private final Channel notifications;
    private final Channel api;

    public Loggers(JavaPlugin plugin) {
        this(plugin.getLogger());
    }

    public Loggers(Logger logger) {
        lifecycle = new Channel(logger, "lifecycle");
        config = new Channel(logger, "config");
        trade = new Channel(logger, "trade");
        shop = new Channel(logger, "shop");
        gui = new Channel(logger, "gui");
        integrations = new Channel(logger, "integrations");
        sound = new Channel(logger, "sound");
        notifications = new Channel(logger, "notifications");
        api = new Channel(logger, "api");
    }

    public Channel lifecycle() {
        return lifecycle;
    }

    public Channel config() {
        return config;
    }

    public Channel trade() {
        return trade;
    }

    public Channel shop() {
        return shop;
    }

    public Channel gui() {
        return gui;
    }

    public Channel integrations() {
        return integrations;
    }

    public Channel sound() {
        return sound;
    }

    public Channel notifications() {
        return notifications;
    }

    public Channel api() {
        return api;
    }

    public static class Channel {
        private final Logger logger;
        private final String name;

        private Channel(Logger logger, String name) {
            this.logger = logger;
            this.name = name;
        }

        public void warning(String message) {
            log(Level.WARNING, message, null);
        }

        public void warning(String message, Throwable error) {
            log(Level.WARNING, message, error);
        }

        public void severe(String message, Throwable error) {
            log(Level.SEVERE, message, error);
        }

        public void log(Level level, String message, Throwable error) {
            logger.log(level, "[" + name + "] " + message, error);
        }
    }
}
