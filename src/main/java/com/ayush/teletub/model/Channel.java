package com.ayush.teletub.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Channel {
    private String id;      // e.g. "dlhd_51"
    private String name;    // e.g. "Sky Sports 1"
    private int watchId;    // the ?id=N value from /watch.php?id=N
    private String logo;    // optional logo URL
}
