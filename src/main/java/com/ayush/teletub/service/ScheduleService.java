package com.ayush.teletub.service;

import com.ayush.teletub.model.ScheduleEvent;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ScheduleService {

    private static final String SCHEDULE_URL = "https://dlhd.pk/";
    private static final Pattern WATCH_ID = Pattern.compile("[?&]id=(\\d+)");

    private final PlaywrightService playwright;

    @Lazy @Autowired ScheduleService self;

    public ScheduleService(PlaywrightService playwright) {
        this.playwright = playwright;
    }

    @Cacheable(value = "schedule", unless = "#result.isEmpty()")
    public List<ScheduleEvent> getSchedule() throws Exception {
        log.info("Scraping sports schedule from {}", SCHEDULE_URL);
        return playwright.withContext(this::scrapeSchedule);
    }

    public ScheduleEvent findById(String id) throws Exception {
        return self.getSchedule().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    private List<ScheduleEvent> scrapeSchedule(BrowserContext ctx) {
        List<ScheduleEvent> events = new ArrayList<>();

        try (Page page = ctx.newPage()) {
            page.navigate(SCHEDULE_URL, new Page.NavigateOptions()
                    .setTimeout(30_000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            page.waitForSelector(".schedule__event",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));

            List<ElementHandle> days = page.querySelectorAll(".schedule__day");
            log.info("Found {} schedule days", days.size());

            for (ElementHandle day : days) {
                ElementHandle dayTitleEl = day.querySelector(".schedule__dayTitle");
                String dayTitle = dayTitleEl != null ? dayTitleEl.innerText().trim() : "Today";

                List<ElementHandle> categories = day.querySelectorAll(".schedule__category");
                for (ElementHandle category : categories) {
                    ElementHandle catHeaderEl = category.querySelector(".schedule__catHeader .card__meta");
                    String categoryName = catHeaderEl != null ? catHeaderEl.innerText().trim() : "";

                    List<ElementHandle> scheduleEvents = category.querySelectorAll(".schedule__event");
                    for (ElementHandle eventEl : scheduleEvents) {
                        ElementHandle timeEl = eventEl.querySelector(".schedule__time");
                        ElementHandle titleEl = eventEl.querySelector(".schedule__eventTitle");

                        String timeUtc = timeEl != null ? timeEl.getAttribute("data-time") : "";
                        String eventTitle = titleEl != null ? titleEl.innerText().trim() : "";

                        if (eventTitle.isBlank()) continue;

                        List<ScheduleEvent.ChannelRef> channels = new ArrayList<>();
                        List<ElementHandle> channelLinks = eventEl.querySelectorAll(
                                ".schedule__channels a[href*='/watch.php?id=']");

                        for (ElementHandle link : channelLinks) {
                            String href = link.getAttribute("href");
                            if (href == null) continue;
                            Matcher m = WATCH_ID.matcher(href);
                            if (!m.find()) continue;

                            int watchId = Integer.parseInt(m.group(1));
                            String chName = link.getAttribute("title");
                            if (chName == null || chName.isBlank()) chName = link.innerText().trim();

                            channels.add(ScheduleEvent.ChannelRef.builder()
                                    .watchId(watchId)
                                    .name(chName)
                                    .build());
                        }

                        if (channels.isEmpty()) continue;

                        String id = "dlhd_sched_" + stableId(eventTitle + timeUtc);
                        events.add(ScheduleEvent.builder()
                                .id(id)
                                .title(eventTitle)
                                .category(categoryName)
                                .day(dayTitle)
                                .timeUtc(timeUtc)
                                .channels(channels)
                                .build());
                    }
                }
            }
        }

        log.info("Scraped {} schedule events", events.size());
        return events;
    }

    /** Short deterministic hex string from the input — used to build stable IDs. */
    private String stableId(String input) {
        int hash = input.hashCode();
        return HexFormat.of().toHexDigits(hash);
    }
}
