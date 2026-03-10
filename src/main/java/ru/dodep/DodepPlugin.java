package ru.dodep;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;

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
    private String startupIssue = "";
    private int activeWebhookPort = -1;

    @Override
    public void onEnable() {
        getLogger().info("onEnable() start");
        saveDefaultConfig();
        this.donationLogger = createDonationLogger();
        this.webhookHandler = new DonationWebhookHandler(this, donationLogger);
        startWebhookServer();

        PluginCommand testCommand = getCommand("dodeptest");
        if (testCommand == null) {
            getLogger().severe("dodeptest command is missing in plugin.yml");
        } else {
            testCommand.setExecutor(new DodepTestCommand(this));
        }

        PluginCommand statusCommand = getCommand("dodepstatus");
        if (statusCommand == null) {
            getLogger().severe("dodepstatus command is missing in plugin.yml");
        } else {
            statusCommand.setExecutor((sender, command, label, args) -> {
                String host = getConfig().getString("webhook.host", "0.0.0.0");
                int port = activeWebhookPort > 0 ? activeWebhookPort : getConfig().getInt("webhook.port", 8787);
                String path = getConfig().getString("webhook.path", "/donationalerts");
                boolean configured = !getEffectiveWebhookToken().isBlank();
                boolean webhookUp = webhookServer != null;
                sender.sendMessage("[Dodep] loaded=true webhookUp=" + webhookUp + " endpoint=http://" + host + ":" + port + path + " tokenConfigured=" + configured);
                if (!startupIssue.isBlank()) {
                    sender.sendMessage("[Dodep] startupIssue=" + startupIssue);
                }
                return true;
            });
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
        int configuredPort = getConfig().getInt("webhook.port", 8787);
        int maxPortRetries = Math.max(0, getConfig().getInt("webhook.max-port-retries", 0));
        String path = getConfig().getString("webhook.path", "/donationalerts");

        IOException lastError = null;
        for (int attempt = 0; attempt <= maxPortRetries; attempt++) {
            int port = configuredPort + attempt;
            try {
                webhookServer = HttpServer.create(new InetSocketAddress(host, port), 0);
                webhookServer.createContext(path, webhookHandler);
                webhookServer.createContext("/health", exchange -> {
                    byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.getResponseBody().close();
                });
                webhookServer.setExecutor(Executors.newFixedThreadPool(2));
                webhookServer.start();
                activeWebhookPort = port;
                startupIssue = "";
                if (attempt > 0) {
                    getLogger().warning("Configured port " + configuredPort + " is busy. Switched to free port " + port);
                }
                getLogger().info("Webhook listening on http://" + host + ":" + port + path);
                getLogger().info("Healthcheck available at http://" + host + ":" + port + "/health");
                return;
            } catch (IOException e) {
                lastError = e;
                webhookServer = null;
            }
        }

        activeWebhookPort = -1;
        startupIssue = "Failed to start webhook server: " + (lastError == null ? "unknown error" : lastError.getMessage());
        getLogger().log(Level.SEVERE, "Failed to start webhook server", lastError);
    }


    private void logStartupDiagnostics() {
        String host = getConfig().getString("webhook.host", "0.0.0.0");
        int port = activeWebhookPort > 0 ? activeWebhookPort : getConfig().getInt("webhook.port", 8787);
        String path = getConfig().getString("webhook.path", "/donationalerts");
        String token = getEffectiveWebhookToken();
        String tokenSource = (getConfig().getString("webhook.token", "").isBlank()) ? "donationalerts.widget-url token" : "webhook.token";

        if (webhookServer == null) {
            getLogger().severe("Webhook server is NOT running. Check port/bind errors above.");
            if (!startupIssue.isBlank()) {
                getLogger().severe("startupIssue=" + startupIssue);
            }
        } else {
            getLogger().info("Webhook endpoint: http://" + host + ":" + port + path);
            getLogger().info("Token source: " + tokenSource + "; token configured=" + (!token.isBlank()));
            getLogger().info("Manual test in game: /dodeptest <player> <amount>");
        }
    }


    public boolean isWebhookAvailable() {
        return webhookHandler != null;
    }

    public boolean isWebhookServerRunning() {
        return webhookServer != null;
    }

    public void triggerDonationFromCommand(String playerName, int amount, String source) {
        if (webhookHandler == null) {
            donationLogger.severe("Cannot trigger donation: webhook handler is unavailable. " + startupIssue);
            return;
        }
        webhookHandler.triggerDonation(playerName, amount, source);
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
