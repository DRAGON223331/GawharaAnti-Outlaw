package io.github.gawhara.antoutlaw;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Link inspection for chat messages. Pure logic (no Bukkit), easy to test. */
final class ChatGuard {
    record LinkResult(String url, String reason, boolean invite) {}

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>]+");

    private final Set<String> extensions;
    private final Set<String> hosts;
    private final boolean blockBare;
    private final boolean blockInvites;
    private final Pattern bare;

    ChatGuard(Set<String> extensions, Set<String> hosts, Set<String> tlds, boolean blockBare, boolean blockInvites) {
        this.extensions = extensions;
        this.hosts = hosts;
        this.blockBare = blockBare && !tlds.isEmpty();
        this.blockInvites = blockInvites;
        String alt = String.join("|", tlds.stream().map(Pattern::quote).toList());
        this.bare = alt.isEmpty() ? null : Pattern.compile(
                "(?i)(?<![\\w@.-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:" + alt + ")\\b(?:[/:?#][^\\s<>]*)?");
    }

    /** Turns "site (dot) com", "site[.]com", "site dot com" into "site.com". */
    static String deobfuscate(String s) {
        s = s.replaceAll("(?i)\\s*[\\[({]\\s*(?:dot|\\.)\\s*[\\])}]\\s*", ".");
        s = s.replaceAll("(?i)(\\w)\\s+dot\\s+(?=[a-z]{2,6}\\b)", "$1.");
        return s;
    }

    Optional<LinkResult> findBlocked(String message) {
        String text = deobfuscate(message);
        Matcher m = URL.matcher(text);
        while (m.find()) {
            LinkResult r = evaluate(m.group());
            if (r != null) return Optional.of(r);
        }
        if (blockBare && bare != null) {
            Matcher b = bare.matcher(text);
            while (b.find()) {
                LinkResult r = evaluate(b.group());
                if (r != null) return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** @return null if allowed, otherwise the block reason. */
    private LinkResult evaluate(String raw) {
        String cleaned = raw.replaceAll("[),.!?;:\"'»]+$", "");
        if (cleaned.isEmpty()) return null;
        String candidate = cleaned.regionMatches(true, 0, "http", 0, 4) ? cleaned : "https://" + cleaned;
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            return new LinkResult(cleaned, "Malformed link", false);
        }
        String host = uri.getHost();
        if (host == null) return new LinkResult(cleaned, "Unreadable host", false);
        host = host.toLowerCase(Locale.ROOT);
        String path = Optional.ofNullable(uri.getPath()).orElse("");

        boolean invite = host.equals("discord.gg") || host.equals("dsc.gg") || host.equals("invite.gg")
                || ((host.equals("discord.com") || host.endsWith(".discord.com")
                || host.equals("discordapp.com") || host.endsWith(".discordapp.com")) && path.startsWith("/invite"));
        if (blockInvites && invite) return new LinkResult(cleaned, "Discord invite link", true);

        if (!host.chars().allMatch(c -> c < 128)) {
            return new LinkResult(cleaned, "Non-ASCII (look-alike) domain `" + host + "`", invite);
        }

        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        int dot = lastSegment.lastIndexOf('.');
        String ext = dot >= 0 ? lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!ext.isEmpty() && extensions.contains(ext)) return null;

        for (String allowed : hosts) {
            String d = allowed.toLowerCase(Locale.ROOT);
            if (host.equals(d) || host.endsWith("." + d)) return null;
        }
        String reason = ext.isEmpty()
                ? "Domain `" + host + "` is not in the allowed list"
                : "File type `." + ext + "` and domain `" + host + "` are not allowed";
        return new LinkResult(cleaned, reason, invite);
    }
}
