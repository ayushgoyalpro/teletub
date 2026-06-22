package com.ayush.teletub.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PlayerUrlService {

    private static final String STREAM_BASE = "https://dlhd.pk/stream/stream-%d.php";
    private static final Pattern DADDY_URL  = Pattern.compile(
            "https?://[^\"'\\s]*daddy\\d+\\.php\\?[^\"'\\s]*(?<![\"'\\s])");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Cacheable(value = "playerUrls", unless = "#result == null")
    public String resolvePlayerUrl(int watchId) {
        String streamPageUrl = String.format(STREAM_BASE, watchId);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(streamPageUrl))
                    .header("User-Agent",
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/124.0 Safari/537.36")
                    .header("Referer", "https://dlhd.pk/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            Matcher m = DADDY_URL.matcher(html);
            if (m.find()) {
                String daddyUrl = m.group();
                log.info("Resolved player URL for watchId={}: {}", watchId, daddyUrl);
                return daddyUrl;
            }

            log.warn("No daddy URL found in stream page for watchId={}; falling back", watchId);
        } catch (Exception e) {
            log.warn("Failed to fetch stream page for watchId={}: {}", watchId, e.getMessage());
        }

        return streamPageUrl; // fallback — still works, just has ads
    }
}
