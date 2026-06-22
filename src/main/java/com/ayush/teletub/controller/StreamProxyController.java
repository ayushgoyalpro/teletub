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
    public ResponseEntity<byte[]> proxyManifest(@PathVariable int watchId) throws Exception {
        String m3u8Url = resolveWithCache(watchId);
        String referer = DLHD_BASE + "/stream/stream-" + watchId + ".php";

        HttpResponse<byte[]> resp = fetch(m3u8Url, referer);

        if (resp.statusCode() != 200) {
            // URL may be stale — evict and re-resolve once
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
    public ResponseEntity<byte[]> proxySegment(
            @RequestParam String url,
            @RequestParam int watchId) throws Exception {

        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        String referer = DLHD_BASE + "/stream/stream-" + watchId + ".php";

        HttpResponse<byte[]> resp = fetch(decoded, referer);

        String contentType = resp.headers().firstValue("Content-Type").orElse("video/mp2t");

        // If the segment is itself a playlist (adaptive master → media), rewrite it too
        if (contentType.contains("mpegurl") || decoded.contains(".m3u8")) {
            String manifest = new String(resp.body(), StandardCharsets.UTF_8);
            String rewritten = rewriteManifest(manifest, decoded, watchId);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(rewritten.getBytes(StandardCharsets.UTF_8));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(resp.body());
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

    /**
     * Rewrites non-comment lines in an m3u8 manifest to route through /stream/segment.
     * Relative URLs are resolved against the manifest's own URL first.
     */
    private String rewriteManifest(String manifest, String baseUrl, int watchId) {
        URI base = URI.create(baseUrl);
        StringBuilder sb = new StringBuilder();

        for (String line : manifest.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                sb.append(line).append("\n");
            } else {
                String absolute = trimmed.startsWith("http") ? trimmed : base.resolve(trimmed).toString();
                String encoded = URLEncoder.encode(absolute, StandardCharsets.UTF_8);
                sb.append("/stream/segment?url=").append(encoded)
                  .append("&watchId=").append(watchId).append("\n");
            }
        }

        return sb.toString();
    }
}