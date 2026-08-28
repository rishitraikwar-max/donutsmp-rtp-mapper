package com.donutsmp.rtpmapper.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends asynchronous Discord webhook notifications for discovered bases. */
public final class WebhookNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger("WebhookNotifier");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RtpMapper-WebhookNotifier");
        thread.setDaemon(true);
        return thread;
    });

    private WebhookNotifier() {
    }

    public static void sendBaseAlert(String webhookUrl, double x, double y, double z, String dimension, List<String> blocks) {
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("http")) {
            return;
        }

        EXECUTOR.submit(() -> {
            try {
                JsonObject root = new JsonObject();
                root.addProperty("username", "RTP Base Hunter");

                JsonObject embed = new JsonObject();
                embed.addProperty("title", "🎯 Player Base / Spawner Detected!");
                embed.addProperty("color", 0x57D3FF); // Accent cyan

                StringBuilder desc = new StringBuilder();
                desc.append("**Coordinates:** `X: ").append(Math.round(x))
                        .append(", Y: ").append(Math.round(y))
                        .append(", Z: ").append(Math.round(z)).append("`\n");
                desc.append("**Dimension:** `").append(dimension).append("`\n");
                if (blocks != null && !blocks.isEmpty()) {
                    desc.append("**Detected Blocks:**\n");
                    for (String b : blocks) {
                        desc.append("• `").append(b.replace("minecraft:", "")).append("`\n");
                    }
                }
                embed.addProperty("description", desc.toString());

                JsonArray embeds = new JsonArray();
                embeds.add(embed);
                root.add("embeds", embeds);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl.trim()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    LOGGER.info("[RTP Mapper] Dispatched Discord webhook alert for base at X: {}, Z: {}", Math.round(x), Math.round(z));
                } else {
                    LOGGER.warn("[RTP Mapper] Discord webhook returned status {}: {}", response.statusCode(), response.body());
                }
            } catch (Exception e) {
                LOGGER.warn("[RTP Mapper] Failed to send Discord webhook: {}", e.getMessage());
            }
        });
    }

    public static void sendCenterLandingAlert(String webhookUrl, double x, double y, double z, double distance) {
        if (webhookUrl == null || webhookUrl.isBlank() || !webhookUrl.startsWith("http")) {
            return;
        }

        EXECUTOR.submit(() -> {
            try {
                JsonObject root = new JsonObject();
                root.addProperty("username", "RTP Base Hunter");

                JsonObject embed = new JsonObject();
                embed.addProperty("title", "📍 Landed Within 50k Radius!");
                embed.addProperty("color", 0x6EE7A8); // Green

                String desc = "**Coordinates:** `X: " + Math.round(x) + ", Y: " + Math.round(y) + ", Z: " + Math.round(z) + "`\n" +
                        "**Distance from Spawn (0,0):** `" + Math.round(distance) + " blocks`\n" +
                        "**Status:** Starting Baritone Base Hunt...";
                embed.addProperty("description", desc);

                JsonArray embeds = new JsonArray();
                embeds.add(embed);
                root.add("embeds", embeds);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl.trim()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(root)))
                        .build();

                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
            }
        });
    }
}
