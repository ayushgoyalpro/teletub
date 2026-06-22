package com.ayush.teletub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StremioMeta {

    private String id;
    private String type;
    private String name;
    private String poster;
    private String background;
    private String logo;
    private String description;
    private List<String> genres;

    // Wraps a single meta for /meta/{type}/{id}.json
    @Data
    @Builder
    public static class MetaResponse {
        private StremioMeta meta;
    }

    // Wraps a list for /catalog/{type}/{id}.json
    @Data
    @Builder
    public static class CatalogResponse {
        private List<StremioMeta> metas;
    }
}
