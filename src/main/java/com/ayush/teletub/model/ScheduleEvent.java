package com.ayush.teletub.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ScheduleEvent {
    private String id;           // e.g. "dlhd_sched_a3f2b1"
    private String title;        // e.g. "FIFA World Cup 2026 — Argentina vs Austria"
    private String category;     // e.g. "⚽ FIFA World Cup 2026"
    private String day;          // e.g. "Monday 22nd June 2026"
    private String timeUtc;      // data-time value, e.g. "16:00"
    private List<ChannelRef> channels;

    @Data
    @Builder
    public static class ChannelRef {
        private int watchId;     // /watch.php?id=N
        private String name;     // channel display name
    }
}
