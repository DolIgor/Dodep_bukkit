package ru.dodep;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class DodepPlugin extends JavaPlugin {
    private HttpServer webhookServer;
    private Logger donationLogger;
    private DonationWebhookHandler webhookHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.donationLogger = createDonationLogger();
        startWebhookServer();
        if (getCommand("dodeptest") != null && webhookHandler != null) {
            getCommand("dodeptest").setExecutor(new DodepTestCommand(webhookHandler));
        }
        logStartupDiagnostics();
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
        logger.setUseParentHandlers(true);
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Failed to create plugin data folder: " + getDataFolder().getAbsolutePath());
            }
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
            this.webhookHandler = new DonationWebhookHandler(this, donationLogger);
            webhookServer.createContext(path, webhookHandler);
            webhookServer.createContext("/health", exchange -> {
                byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            });
            webhookServer.setExecutor(Executors.newFixedThreadPool(2));
            webhookServer.start();
            getLogger().info("Webhook listening on http://" + host + ":" + port + path);
            getLogger().info("Healthcheck available at http://" + host + ":" + port + "/health");
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to start webhook server", e);
        }
    }


    private void logStartupDiagnostics() {
        String host = getConfig().getString("webhook.host", "0.0.0.0");
        int port = getConfig().getInt("webhook.port", 8787);
        String path = getConfig().getString("webhook.path", "/donationalerts");
        String token = getEffectiveWebhookToken();
        String tokenSource = (getConfig().getString("webhook.token", "").isBlank()) ? "donationalerts.widget-url token" : "webhook.token";

        if (webhookServer == null) {
            getLogger().severe("Webhook server is NOT running. Check port/bind errors above.");
        } else {
            getLogger().info("Webhook endpoint: http://" + host + ":" + port + path);
            getLogger().info("Token source: " + tokenSource + "; token configured=" + (!token.isBlank()));
            getLogger().info("Manual test in game: /dodeptest <player> <amount>");
        }
    }

    public String getEffectiveWebhookToken() {
        String configToken = getConfig().getString("webhook.token", "");
        if (configToken != null && !configToken.isBlank()) {
            return configToken;
        }

        String widgetUrl = getConfig().getString("donationalerts.widget-url", "");
        if (widgetUrl == null || widgetUrl.isBlank()) {
            return "";
        }

        try {
            URI uri = new URI(widgetUrl);
            String query = uri.getQuery();
            if (query == null || query.isBlank()) {
                return "";
            }

            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && "token".equals(parts[0])) {
                    return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
        } catch (URISyntaxException e) {
            getLogger().warning("Invalid donationalerts.widget-url in config: " + e.getMessage());
        }

        return "";
    }
}
