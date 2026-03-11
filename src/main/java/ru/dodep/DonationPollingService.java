package ru.dodep;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

public class DonationPollingService {
    private final DodepPlugin plugin;
    private final DonationWebhookHandler handler;
    private final Logger logger;
    private final HttpClient httpClient;
    private BukkitTask task;
    private volatile String lastPollStatus = "not_started";

    public DonationPollingService(DodepPlugin plugin, DonationWebhookHandler handler, Logger logger) {
        this.plugin = plugin;
        this.handler = handler;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void start() {
        stop();
        int intervalSeconds = Math.max(5, plugin.getConfig().getInt("polling.interval-seconds", 20));
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, 20L, intervalSeconds * 20L);
        logger.info("Polling service started with interval=" + intervalSeconds + "s");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public String getLastPollStatus() {
        return lastPollStatus;
    }

    private void pollOnce() {
        String apiUrl = plugin.getConfig().getString("polling.api-url", "");
        if (apiUrl == null || apiUrl.isBlank()) {
            lastPollStatus = "polling.api-url is empty";
            return;
        }

        String token = plugin.getConfig().getString("polling.token", "");
        String headerName = plugin.getConfig().getString("polling.token-header", "Authorization");
        String headerPrefix = plugin.getConfig().getString("polling.token-prefix", "Bearer ");

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .header("Accept", "application/json");

            if (!token.isBlank()) {
                requestBuilder.header(headerName, headerPrefix + token);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                lastPollStatus = "http_status=" + response.statusCode();
                return;
            }

            List<DonationEvent> events = parseEvents(response.body());
            if (events.isEmpty()) {
                lastPollStatus = "ok:no_events";
                return;
            }

            String lastSeenId = plugin.getConfig().getString("polling.last-seen-id", "");
            events.sort(Comparator.comparing(DonationEvent::id));

            int processed = 0;
            String newLastSeenId = lastSeenId;
            for (DonationEvent event : events) {
                if (!lastSeenId.isBlank() && event.id().compareTo(lastSeenId) <= 0) {
                    continue;
                }
                processed++;
                newLastSeenId = event.id();
                String source = "polling-api:" + event.id();
                Bukkit.getScheduler().runTask(plugin, () -> handler.triggerDonation(event.player(), event.amount(), source));
            }

            if (processed > 0) {
                plugin.getConfig().set("polling.last-seen-id", newLastSeenId);
                plugin.saveConfig();
                lastPollStatus = "ok:processed=" + processed;
            } else {
                lastPollStatus = "ok:no_new_events";
            }

        } catch (Exception e) {
            lastPollStatus = "error:" + e.getClass().getSimpleName();
            logger.warning("Polling failed: " + e.getMessage());
        }
    }

    private List<DonationEvent> parseEvents(String body) throws IOException {
        JsonElement rootElement = JsonParser.parseString(body);
        JsonArray array = extractArray(rootElement);
        List<DonationEvent> events = new ArrayList<>();

        String idField = plugin.getConfig().getString("polling.fields.id", "id");
        String playerField = plugin.getConfig().getString("polling.fields.player", "username");
        String amountField = plugin.getConfig().getString("polling.fields.amount", "amount");

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String id = readString(obj, idField, "id", "_id", "alert_id", "created_at", "timestamp");
            String player = readString(obj, playerField, "username", "name", "nickname", "sender", "player");
            double amountDouble = readNumber(obj, amountField, "amount", "sum", "amount_main", "amountInUserCurrency");
            int amount = (int) Math.floor(amountDouble);
            if (id == null || id.isBlank()) {
                continue;
            }
            if (player == null || player.isBlank() || amount <= 0) {
                continue;
            }
            events.add(new DonationEvent(id, player, amount));
        }
        return events;
    }

    private JsonArray extractArray(JsonElement root) {
        String listPath = plugin.getConfig().getString("polling.list-path", "data");

        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has(listPath) && object.get(listPath).isJsonArray()) {
                return object.getAsJsonArray(listPath);
            }
            if (object.has("alerts") && object.get("alerts").isJsonArray()) {
                return object.getAsJsonArray("alerts");
            }
            if (object.has("data") && object.get("data").isJsonArray()) {
                return object.getAsJsonArray("data");
            }
            if (object.has("items") && object.get("items").isJsonArray()) {
                return object.getAsJsonArray("items");
            }
        }
        return new JsonArray();
    }

    private String readString(JsonObject root, String firstKey, String... fallback) {
        List<String> keys = new ArrayList<>();
        if (firstKey != null && !firstKey.isBlank()) {
            keys.add(firstKey);
        }
        for (String k : fallback) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }

        for (String key : keys) {
            if (root.has(key) && !root.get(key).isJsonNull()) {
                return root.get(key).getAsString();
            }
        }
        if (root.has("data") && root.get("data").isJsonObject()) {
            return readString(root.getAsJsonObject("data"), firstKey, fallback);
        }
        return null;
    }

    private double readNumber(JsonObject root, String firstKey, String... fallback) {
        List<String> keys = new ArrayList<>();
        if (firstKey != null && !firstKey.isBlank()) {
            keys.add(firstKey);
        }
        for (String k : fallback) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }

        for (String key : keys) {
            if (root.has(key) && !root.get(key).isJsonNull()) {
                return root.get(key).getAsDouble();
            }
        }
        if (root.has("data") && root.get("data").isJsonObject()) {
            return readNumber(root.getAsJsonObject("data"), firstKey, fallback);
        }
        return 0;
    }

    private record DonationEvent(String id, String player, int amount) {
    }
}
