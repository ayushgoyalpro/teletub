package com.ayush.teletub.controller;

import com.ayush.teletub.service.StreamResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StreamProxyController {

    private static final String DLHD_BASE = "https://dlhd.pk";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final long CACHE_TTL_MS = 20 * 60 * 1000L;

    private record CachedM3u8(String url, long resolvedAt) {}

    private final StreamResolverService streamResolver;
    private final ConcurrentHashMap<Integer, CachedM3u8> m3u8Cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @GetMapping("/stream/manifest/{watchId}")
    public ResponseEntity<byte[]> manifest(@PathVariable int watchId) throws Exception {
        String m3u8Url = resolveWithCache(watchId);
        String referer = DLHD_BASE + "/stream/stream-" + watchId + ".php";

        HttpResponse<byte[]> resp = fetch(m3u8Url, referer);

        if (resp.statusCode() != 200) {
            m3u8Cache.remove(watchId);
            m3u8Url = resolveWithCache(watchId);
            resp = fetch(m3u8Url, referer);
        }

        String manifest = new String(resp.body(), StandardCharsets.UTF_8);
        String rewritten = rewriteManifest(manifest, m3u8Url, watchId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.mpegurl")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(rewritten.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/stream/segment")
    public ResponseEntity<byte[]> segment(
            @RequestParam String url,
            @RequestParam int watchId) throws Exception {

        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        String referer = DLHD_BASE + "/stream/stream-" + watchId + ".php";

        HttpResponse<byte[]> resp = fetch(decoded, referer);

        String contentType = resp.headers().firstValue("Content-Type").orElse("video/mp2t");
        byte[] body = resp.body();

        // Detect sub-manifests via content-type, URL suffix, OR magic bytes — covers CDNs
        // that return application/octet-stream or query-param URLs without ".m3u8"
        boolean isPlaylist = contentType.contains("mpegurl")
                || decoded.contains(".m3u8")
                || (body.length >= 7 && new String(body, 0, 7, StandardCharsets.US_ASCII).equals("#EXTM3U"));

        if (isPlaylist) {
            String rewritten = rewriteManifest(new String(body, StandardCharsets.UTF_8), decoded, watchId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/vnd.apple.mpegurl")
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(rewritten.getBytes(StandardCharsets.UTF_8));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(body);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String resolveWithCache(int watchId) throws Exception {
        CachedM3u8 cached = m3u8Cache.get(watchId);
        if (cached != null && System.currentTimeMillis() - cached.resolvedAt() < CACHE_TTL_MS) {
            return cached.url();
        }

        // Deduplicate: concurrent requests for the same watchId share one Playwright call
        CompletableFuture<String> future = inFlight.computeIfAbsent(watchId, id ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        log.info("Resolving m3u8 for watchId={}", id);
                        String url = streamResolver.resolveM3u8(id);
                        m3u8Cache.put(id, new CachedM3u8(url, System.currentTimeMillis()));
                        return url;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        inFlight.remove(id);
                    }
                })
        );

        return future.get();
    }

    private HttpResponse<byte[]> fetch(String url, String referer) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Referer", referer)
                .header("Origin", DLHD_BASE)
                .header("User-Agent", UA)
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static final java.util.regex.Pattern URI_ATTR =
            java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");

    private String rewriteManifest(String manifest, String baseUrl, int watchId) {
        URI base = URI.create(baseUrl);
        StringBuilder sb = new StringBuilder();

        for (String line : manifest.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                sb.append(line).append("\n");
            } else if (trimmed.startsWith("#")) {
                // Rewrite URI="..." attributes inside tags like #EXT-X-MEDIA and #EXT-X-MAP
                sb.append(rewriteUriAttributes(line, base, watchId)).append("\n");
            } else {
                String absolute = trimmed.startsWith("http") ? trimmed : base.resolve(trimmed).toString();
                String encoded = URLEncoder.encode(absolute, StandardCharsets.UTF_8);
                sb.append("/stream/segment?url=").append(encoded)
                  .append("&watchId=").append(watchId).append("\n");
            }
        }

        return sb.toString();
    }

    private String rewriteUriAttributes(String line, URI base, int watchId) {
        java.util.regex.Matcher m = URI_ATTR.matcher(line);
        if (!m.find()) return line;
        StringBuffer sb = new StringBuffer();
        do {
            String original = m.group(1);
            String absolute = original.startsWith("http") ? original : base.resolve(original).toString();
            String encoded = URLEncoder.encode(absolute, StandardCharsets.UTF_8);
            String proxy = "/stream/segment?url=" + encoded + "&watchId=" + watchId;
            m.appendReplacement(sb, "URI=\"" + proxy + "\"");
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }
}
