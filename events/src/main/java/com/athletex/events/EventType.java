package com.athletex.events;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum EventType {
    @JsonProperty("game_status") GAME_STATUS("game_status"),
    @JsonProperty("stat_line") STAT_LINE("stat_line"),
    @JsonProperty("injury_event") INJURY_EVENT("injury_event"),
    @JsonProperty("news_event") NEWS_EVENT("news_event");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
