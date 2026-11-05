package com.dsh.webview;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Parses what a user pastes into a usable DSH Web URL.
 *
 * <p>The DSH launcher prints a line such as
 * {@code dsh web: http://192.168.1.10:3082/?token=…}. Users copy that line
 * (or just the URL, or a bare host:port) into the setup screen, so the shell
 * accepts all of those forms and rejects anything that is not HTTP(S).</p>
 */
public final class UrlNormalizer {

    private static final String LAUNCHER_PREFIX = "dsh web:";

    private UrlNormalizer() {
    }

    /**
     * @param raw user input, possibly the whole launcher line
     * @return a normalized HTTP(S) URL, or {@code null} when the input is not usable
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = stripQuotes(raw.trim());
        if (value.regionMatches(true, 0, LAUNCHER_PREFIX, 0, LAUNCHER_PREFIX.length())) {
            value = stripQuotes(value.substring(LAUNCHER_PREFIX.length()).trim());
        }
        if (value.isEmpty()) {
            return null;
        }

        String candidate;
        if (hasHttpScheme(value)) {
            candidate = value;
        } else if (value.startsWith("//")) {
            candidate = "http:" + value;
        } else {
            if (looksLikeForeignScheme(value)) {
                return null;
            }
            // A bare host, optional port and path still describes an HTTP server.
            candidate = "http://" + value;
        }

        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
            return null;
        }
        return candidate;
    }

    /**
     * Remove the process launch token from a URL before it is persisted.
     *
     * <p>{@code dsh web} prints a URL whose sole credential is a one-time
     * {@code ?token=…} query parameter; after the exchange the session cookie
     * authorizes later visits, so the stored address should not keep the token.</p>
     *
     * @param url a normalized URL, or {@code null}
     * @return the same URL without a {@code token} parameter, or {@code null}
     */
    public static String stripLaunchToken(String url) {
        if (url == null) {
            return null;
        }
        String fragment = "";
        int hash = url.indexOf('#');
        if (hash >= 0) {
            fragment = url.substring(hash);
            url = url.substring(0, hash);
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return url + fragment;
        }
        String base = url.substring(0, queryStart);
        StringBuilder kept = new StringBuilder();
        for (String parameter : url.substring(queryStart + 1).split("&", -1)) {
            if (parameter.isEmpty() || "token".equals(parameter) || parameter.startsWith("token=")) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(parameter);
        }
        String query = kept.length() == 0 ? "" : "?" + kept;
        return base + query + fragment;
    }

    /**
     * The {@code host:port} part of a URL, with the scheme's default port
     * filled in, so two URLs can be compared without caring about the path.
     *
     * @param url a normalized URL, or {@code null}
     * @return lowercase {@code host:port}, or {@code null} when it cannot be parsed
     */
    public static String authorityOf(String url) {
        if (url == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        int port = uri.getPort();
        if (port == -1) {
            String scheme = uri.getScheme();
            boolean secure = scheme != null && "https".equalsIgnoreCase(scheme);
            port = secure ? 443 : 80;
        }
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /**
     * Whether both URLs point at the same server (ignoring path, query and the
     * one-time launch token).
     */
    public static boolean sameAuthority(String left, String right) {
        String leftAuthority = authorityOf(left);
        return leftAuthority != null && leftAuthority.equals(authorityOf(right));
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static boolean hasHttpScheme(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * Tell an intentional non-HTTP URI such as {@code mailto:…} or
     * {@code javascript:…} apart from a bare {@code host:port}. The latter has a
     * numeric port right after the colon, so it must stay accepted.
     */
    private static boolean looksLikeForeignScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String scheme = value.substring(0, colon);
        if (!scheme.matches("[A-Za-z][A-Za-z0-9+.\\-]*")) {
            return false;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if ("http".equals(lower) || "https".equals(lower)) {
            return false;
        }
        String rest = value.substring(colon + 1);
        return !rest.matches("\\d+(/.*)?");
    }
}
