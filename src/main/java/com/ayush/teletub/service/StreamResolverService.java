package com.ayush.teletub.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamResolverService {

    private static final int RESOLVE_TIMEOUT_MS = 40_000;

    private static final String[] PLAYER_PATHS = {
            "/stream/stream-%d.php",
            "/cast/stream-%d.php",
            "/watch/stream-%d.php",
            "/plus/stream-%d.php",
            "/casting/stream-%d.php",
            "/player/stream-%d.php"
    };

    private final PlaywrightService playwright;

    @Value("${dlhd.base-url}")
    private String dlhdBase;

    @Value("${dlhd.wrapper-origin}")
    private String wrapperOrigin;

    public String resolveM3u8(int watchId) throws Exception {
        for (String pathTemplate : PLAYER_PATHS) {
            String playerUrl = dlhdBase + String.format(pathTemplate, watchId);
            try {
                return tryResolve(playerUrl);
            } catch (Exception e) {
                log.warn("Failed on {}: {}", playerUrl, e.getMessage());
            }
        }
        throw new RuntimeException("Could not resolve m3u8 for watchId=" + watchId);
    }

    private String tryResolve(String playerUrl) throws Exception {
        log.info("Resolving m3u8 via iframe wrapper: {}", playerUrl);
        return playwright.withContext(ctx -> {
            try (Page page = ctx.newPage()) {
                String wrapperUrl = wrapperOrigin + "/?url=";
                page.route(wrapperUrl + "**", route -> route.fulfill(new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/html")
                        .setBody("<!DOCTYPE html><html><body style='margin:0'>" +
                                "<iframe src=\"" + playerUrl + "\" width='100%' height='100%' " +
                                "style='border:none' allowfullscreen allow='autoplay'></iframe>" +
                                "</body></html>")));

                Response m3u8 = page.waitForResponse(
                        resp -> resp.url().contains(".m3u8"),
                        new Page.WaitForResponseOptions().setTimeout(RESOLVE_TIMEOUT_MS),
                        () -> page.navigate(wrapperUrl + playerUrl, new Page.NavigateOptions()
                                .setTimeout(RESOLVE_TIMEOUT_MS)
                                .setWaitUntil(WaitUntilState.COMMIT)));

                String body = m3u8.text();
                if (!body.startsWith("#EXTM3U")) {
                    log.warn("URL {} returned invalid content: {}", m3u8.url(),
                            body.substring(0, Math.min(120, body.length())));
                    throw new RuntimeException("Invalid m3u8 content from " + m3u8.url());
                }

                log.info("Captured m3u8: {}", m3u8.url());
                return m3u8.url();
            }
        });
    }
}