package ru.dodep;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class DodepPlugin extends JavaPlugin {
    private HttpServer webhookServer;
    private Logger donationLogger;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.donationLogger = createDonationLogger();
        startWebhookServer();
        getLogger().info("DodepPlugin enabled");
    }

    @Override
    public void onDisable() {
        if (webhookServer != null) {
            webhookServer.stop(0);
        }
        getLogger().info("DodepPlugin disabled");
    }

    private Logger createDonationLogger() {
        Logger logger = Logger.getLogger("DodepDonations");
        logger.setUseParentHandlers(false);
        try {
            FileHandler fileHandler = new FileHandler(getDataFolder().toPath().resolve("donations.log").toString(), true);
            fileHandler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return String.format("[%1$tF %1$tT] %2$s%n", record.getMillis(), record.getMessage());
                }
            });
            logger.addHandler(fileHandler);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize donation log file", e);
        }
        return logger;
    }

    private void startWebhookServer() {
        String host = getConfig().getString("webhook.host", "0.0.0.0");
        int port = getConfig().getInt("webhook.port", 8787);
        String path = getConfig().getString("webhook.path", "/donationalerts");

        try {
            webhookServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            webhookServer.createContext(path, new DonationWebhookHandler(this, donationLogger));
            webhookServer.setExecutor(Executors.newFixedThreadPool(2));
            webhookServer.start();
            getLogger().info("Webhook listening on http://" + host + ":" + port + path);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to start webhook server", e);
        }
    }
}
