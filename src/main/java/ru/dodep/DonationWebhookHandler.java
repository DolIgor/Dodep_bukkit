package ru.dodep;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

public class DonationWebhookHandler implements HttpHandler {
    private final DodepPlugin plugin;
    private final Logger donationLogger;

    public DonationWebhookHandler(DodepPlugin plugin, Logger donationLogger) {
        this.plugin = plugin;
        this.donationLogger = donationLogger;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        boolean debug = plugin.getConfig().getBoolean("webhook.debug-log-requests", true);
        if (debug) {
            plugin.getLogger().info("Incoming webhook request method=" + exchange.getRequestMethod() + " path=" + exchange.getRequestURI());
        }

        boolean isPost = "POST".equalsIgnoreCase(exchange.getRequestMethod());
        boolean isGet = "GET".equalsIgnoreCase(exchange.getRequestMethod());
        if (!isPost && !isGet) {
            respond(exchange, 405, "Method Not Allowed");
            return;
        }

        String expectedToken = plugin.getEffectiveWebhookToken();
        String incomingToken = Optional.ofNullable(exchange.getRequestHeaders().getFirst("X-DA-Token")).orElse("");
        if (incomingToken.isBlank()) {
            incomingToken = readQueryParam(exchange.getRequestURI().getQuery(), "token");
        }
        if (!expectedToken.isBlank() && !expectedToken.equals(incomingToken)) {
            if (debug) {
                plugin.getLogger().warning("Webhook rejected due to invalid token. Incoming token length=" + incomingToken.length());
            }
            donationLogger.warning("Rejected webhook request: invalid token");
            respond(exchange, 403, "Forbidden");
            return;
        }

        DonationPayload payload;
        if (isGet) {
            payload = parsePayloadFromQuery(exchange.getRequestURI().getQuery());
            if (payload == null) {
                respond(exchange, 400, "Bad Request: use ?player=Nick&amount=1234");
                return;
            }
        } else {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                payload = parsePayload(body);
            } catch (IllegalArgumentException e) {
                donationLogger.warning("Invalid webhook payload: " + e.getMessage());
                respond(exchange, 400, "Bad Request: " + e.getMessage());
                return;
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> processDonation(payload));
        respond(exchange, 200, "OK");
    }

    private DonationPayload parsePayload(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String player = readString(root, "player", "username", "name", "sender", "nickname");
        double amountDouble = readNumber(root, "amount", "sum", "amount_main", "amountInUserCurrency");
        int amount = (int) Math.floor(amountDouble);

        if (player == null || player.isBlank()) {
            throw new IllegalArgumentException("player is missing");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        return new DonationPayload(player, amount);
    }

    private String readString(JsonObject root, String... keys) {
        for (String key : keys) {
            JsonElement element = root.get(key);
            if (element != null && !element.isJsonNull()) {
                return element.getAsString();
            }
        }
        if (root.has("data") && root.get("data").isJsonObject()) {
            return readString(root.getAsJsonObject("data"), keys);
        }
        return null;
    }

    private DonationPayload parsePayloadFromQuery(String query) {
        String player = readQueryParam(query, "player");
        String amountString = readQueryParam(query, "amount");
        if (player.isBlank() || amountString.isBlank()) {
            return null;
        }

        int amount;
        try {
            amount = Integer.parseInt(amountString);
        } catch (NumberFormatException e) {
            return null;
        }

        if (amount <= 0) {
            return null;
        }
        return new DonationPayload(player, amount);
    }

    private double readNumber(JsonObject root, String... keys) {
        for (String key : keys) {
            JsonElement element = root.get(key);
            if (element != null && !element.isJsonNull()) {
                return element.getAsDouble();
            }
        }
        if (root.has("data") && root.get("data").isJsonObject()) {
            return readNumber(root.getAsJsonObject("data"), keys);
        }
        return 0;
    }

    private void processDonation(DonationPayload payload) {
        Player player = Bukkit.getPlayerExact(payload.playerName());
        if (player == null) {
            donationLogger.warning("Player " + payload.playerName() + " is offline; donation " + payload.amount() + " queued was skipped");
            return;
        }

        int amount = payload.amount();
        Map<String, Integer> rewards = new LinkedHashMap<>();
        rewards.put(plugin.getConfig().getString("items.thousands", "casinochips:rich_7"), (amount / 1000) % 10);
        rewards.put(plugin.getConfig().getString("items.hundreds", "casinochips:rich_5"), (amount / 100) % 10);
        rewards.put(plugin.getConfig().getString("items.tens", "casinochips:rich_3"), (amount / 10) % 10);
        rewards.put(plugin.getConfig().getString("items.ones", "casinochips:rich"), amount % 10);

        rewards.forEach((itemId, count) -> {
            if (count <= 0) {
                return;
            }
            String command = String.format("minecraft:give %s %s %d", player.getName(), itemId, count);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        });

        Bukkit.broadcastMessage(ChatColor.GOLD + payload.playerName() + ChatColor.YELLOW + " сделал додеп на сумму " + ChatColor.GREEN + amount);

        boolean allGood = verifyRewards(player, rewards);
        if (allGood) {
            donationLogger.info("SUCCESS player=" + payload.playerName() + " amount=" + amount + " rewards=" + rewards);
        } else {
            donationLogger.warning("MISMATCH player=" + payload.playerName() + " amount=" + amount + " rewards=" + rewards);
        }
    }

    private boolean verifyRewards(Player player, Map<String, Integer> expectedRewards) {
        boolean allGood = true;
        for (Map.Entry<String, Integer> entry : expectedRewards.entrySet()) {
            String itemId = entry.getKey();
            int expected = entry.getValue();
            if (expected <= 0) {
                continue;
            }

            Material material = Registry.MATERIAL.get(org.bukkit.NamespacedKey.fromString(itemId));
            if (material == null) {
                donationLogger.warning("Cannot verify item " + itemId + " for player=" + player.getName() + ": unknown material id");
                allGood = false;
                continue;
            }

            int actual = 0;
            for (ItemStack stack : player.getInventory().getContents()) {
                if (stack != null && stack.getType() == material) {
                    actual += stack.getAmount();
                }
            }

            if (actual < expected) {
                donationLogger.warning("Missing items player=" + player.getName() + " item=" + itemId + " expected=" + expected + " actual=" + actual);
                allGood = false;
            }
        }
        return allGood;
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private String readQueryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && key.equals(parts[0])) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private record DonationPayload(String playerName, int amount) {
    }
}
