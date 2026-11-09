package io.github.gawhara.antoutlaw;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

/**
 * Sends Discord webhook messages from a single background worker thread.
 * Handles embeds, PNG attachments (multipart) and HTTP 429 rate limits.
 */
final class DiscordNotifier {

    static final int RED = 0xE74C3C, ORANGE = 0xE67E22, YELLOW = 0xF1C40F,
            GREEN = 0x2ECC71, BLUE = 0x3498DB, GRAY = 0x95A5A6, PURPLE = 0x9B59B6;

    record Field(String name, String value, boolean inline) {}

    /** Fluent embed builder. */
    static final class Embed {
        private String title, description, footer, thumbnail, imageAttachment;
        private int color = GRAY;
        private final List<Field> fields = new ArrayList<>();

        Embed title(String v) { title = v; return this; }
        Embed description(String v) { description = v; return this; }
        Embed color(int v) { color = v; return this; }
        Embed footer(String v) { footer = v; return this; }
        Embed thumbnail(String url) { thumbnail = url; return this; }
        Embed imageAttachment(String filename) { imageAttachment = filename; return this; }
        Embed field(String name, String value) { return field(name, value, false); }
        Embed field(String name, String value, boolean inline) {
            if (fields.size() < 25) fields.add(new Field(name, value, inline));
            return this;
        }

        String toJson() {
            StringBuilder sb = new StringBuilder("{");
            if (title != null) sb.append("\"title\":").append(Json.str(cut(title, 256))).append(',');
            if (description != null) sb.append("\"description\":").append(Json.str(cut(description, 4000))).append(',');
            sb.append("\"color\":").append(color).append(',');
            sb.append("\"timestamp\":").append(Json.str(Instant.now().toString())).append(',');
            if (footer != null) sb.append("\"footer\":{\"text\":").append(Json.str(cut(footer, 2000))).append("},");
            if (thumbnail != null) sb.append("\"thumbnail\":{\"url\":").append(Json.str(thumbnail)).append("},");
            if (imageAttachment != null)
                sb.append("\"image\":{\"url\":").append(Json.str("attachment://" + imageAttachment)).append("},");
            sb.append("\"fields\":[");
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                String name = f.name() == null || f.name().isBlank() ? "​" : cut(f.name(), 250);
                String value = f.value() == null || f.value().isBlank() ? "​" : cut(f.value(), 1020);
                if (i > 0) sb.append(',');
                sb.append("{\"name\":").append(Json.str(name))
                  .append(",\"value\":").append(Json.str(value))
                  .append(",\"inline\":").append(f.inline()).append('}');
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    private record Job(String json, byte[] image, String imageName) {}

    private static final Job POISON = new Job(null, null, null);

    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>(200);
    private Thread worker;
    private volatile boolean enabled;
    private volatile String webhook = "";
    private volatile String username = "GawharaAnti-outlaw";

    DiscordNotifier(Logger log) {
        this.log = log;
    }

    void configure(boolean enabled, String webhookUrl, String username) {
        this.enabled = enabled;
        this.webhook = webhookUrl == null ? "" : webhookUrl.trim();
        this.username = username == null || username.isBlank() ? "GawharaAnti-outlaw" : username;
        if (enabled && !webhook.isEmpty() && !validUrl(webhook)) {
            log.warning("Discord webhook URL is invalid; expected https://discord.com/api/webhooks/...");
        }
    }

    static boolean validUrl(String u) {
        return u.startsWith("https://discord.com/api/webhooks/")
                || u.startsWith("https://discordapp.com/api/webhooks/")
                || u.startsWith("https://canary.discord.com/api/webhooks/")
                || u.startsWith("https://ptb.discord.com/api/webhooks/");
    }

    boolean isConfigured() {
        return enabled && validUrl(webhook);
    }

    int queued() {
        return queue.size();
    }

    void start() {
        if (worker != null) return;
        worker = new Thread(this::run, "GawharaAntiOutlaw-Discord");
        worker.setDaemon(true);
        worker.start();
    }

    /** Flushes pending messages (max ~8s) and stops the worker. */
    void shutdown() {
        if (worker == null) return;
        if (!queue.offer(POISON)) {
            queue.clear();
            queue.offer(POISON);
        }
        try {
            worker.join(8000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker = null;
    }

    void sendText(String content) {
        enqueue(new Job(payload(content, null), null, null));
    }

    void send(Embed embed) {
        enqueue(new Job(payload(null, embed), null, null));
    }

    void send(Embed embed, byte[] png, String filename) {
        if (png == null) {
            send(embed);
            return;
        }
        embed.imageAttachment(filename);
        enqueue(new Job(payload(null, embed), png, filename));
    }

    private void enqueue(Job job) {
        if (!isConfigured()) return;
        if (!queue.offer(job)) log.warning("Discord queue full; dropping a message.");
    }

    String payload(String content, Embed embed) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"username\":").append(Json.str(cut(username, 80)));
        sb.append(",\"allowed_mentions\":{\"parse\":[]}");
        if (content != null) sb.append(",\"content\":").append(Json.str(cut(content, 2000)));
        if (embed != null) sb.append(",\"embeds\":[").append(embed.toJson()).append(']');
        return sb.append('}').toString();
    }

    static byte[] multipart(String boundary, String json, byte[] image, String filename) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(image.length + json.length() + 512);
        write(o, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + json + "\r\n");
        write(o, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""
                + filename + "\"\r\nContent-Type: image/png\r\n\r\n");
        o.writeBytes(image);
        write(o, "\r\n--" + boundary + "--\r\n");
        return o.toByteArray();
    }

    private static void write(ByteArrayOutputStream o, String s) {
        o.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private void run() {
        while (true) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (job == POISON) return;
            deliver(job);
        }
    }

    private void deliver(Job job) {
        String url = webhook;
        if (!validUrl(url)) return;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
                if (job.image() == null) {
                    b.header("Content-Type", "application/json")
                     .POST(HttpRequest.BodyPublishers.ofString(job.json(), StandardCharsets.UTF_8));
                } else {
                    String boundary = "----GAO" + UUID.randomUUID().toString().replace("-", "");
                    b.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                     .POST(HttpRequest.BodyPublishers.ofByteArray(
                             multipart(boundary, job.json(), job.image(), job.imageName())));
                }
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int code = r.statusCode();
                if (code >= 200 && code < 300) return;
                if (code == 429) {
                    sleepQuietly(retryAfterMs(r));
                    continue;
                }
                log.warning("Discord webhook returned HTTP " + code + ": " + cut(r.body(), 200));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warning("Discord webhook failed: " + ex.getMessage());
                return;
            }
        }
    }

    private static long retryAfterMs(HttpResponse<String> r) {
        try {
            String h = r.headers().firstValue("retry-after").orElse("1");
            long ms = (long) (Double.parseDouble(h) * 1000);
            return Math.max(250, Math.min(ms, 10_000));
        } catch (Exception e) {
            return 1000;
        }
    }

    private static void sleepQuietly(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Makes text safe inside a Discord code block / inline text. */
    static String block(String s) {
        return "```\n" + cut(s.replace("```", "'''"), 1000) + "\n```";
    }

    static String inline(String s) {
        return "`" + cut(s.replace('`', '\'').replace('\n', ' '), 200) + "`";
    }
}
