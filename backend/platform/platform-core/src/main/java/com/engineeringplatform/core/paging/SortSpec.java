package com.engineeringplatform.core.paging;
public record SortSpec(String key, SortDirection direction) {
    public SortSpec {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Sort key must not be blank");
        if (direction == null) direction = SortDirection.ASC;
    }
}
