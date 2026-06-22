package com.ayush.teletub.service;

import com.ayush.teletub.model.Channel;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChannelService {

    private static final String CHANNELS_URL = "https://dlhd.pk/24-7-channels.php";
    // Matches href="/watch.php?id=51" or href="https://dlhd.pk/watch.php?id=51"
    private static final Pattern WATCH_ID = Pattern.compile("[?&]id=(\\d+)");

    private final PlaywrightService playwright;

    // Self-reference so findById goes through the proxy and hits the cache
    @Lazy @Autowired ChannelService self;

    public ChannelService(PlaywrightService playwright) {
        this.playwright = playwright;
    }

    @Cacheable(value = "channels", unless = "#result.isEmpty()")
    public List<Channel> getChannels() throws Exception {
        log.info("Scraping 24/7 channel list from {}", CHANNELS_URL);
        return playwright.withContext(ctx -> scrapeChannels(ctx));
    }

    private List<Channel> scrapeChannels(BrowserContext ctx) {
        List<Channel> channels = new ArrayList<>();

        try (Page page = ctx.newPage()) {
            page.navigate(CHANNELS_URL, new Page.NavigateOptions()
                    .setTimeout(30_000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            page.waitForSelector("a.card[href*='/watch.php?id=']",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

            List<ElementHandle> cards = page.querySelectorAll("a.card[href*='/watch.php?id=']");
            log.info("Found {} channel cards", cards.size());

            for (ElementHandle card : cards) {
                String href = card.getAttribute("href");
                if (href == null) continue;

                Matcher m = WATCH_ID.matcher(href);
                if (!m.find()) continue;

                int watchId = Integer.parseInt(m.group(1));

                // Prefer data-title (lowercase), fall back to card__title text
                String name = card.getAttribute("data-title");
                if (name == null || name.isBlank()) {
                    ElementHandle titleEl = card.querySelector(".card__title");
                    name = titleEl != null ? titleEl.innerText().trim() : "Channel " + watchId;
                } else {
                    // data-title is lowercase, capitalise first letter of each word
                    name = titleCase(name);
                }

                channels.add(Channel.builder()
                        .id("dlhd_" + watchId)
                        .name(name)
                        .watchId(watchId)
                        .build());
            }
        }

        log.info("Scraped {} channels", channels.size());
        return channels;
    }

    public Channel findById(String id) throws Exception {
        return self.getChannels().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    private String titleCase(String s) {
        if (s == null || s.isBlank()) return s;
        String[] words = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1));
        }
        return sb.toString();
    }
}
