package com.engineeringplatform.core.paging;

import java.util.List;

public record PageResult<T>(List<T> items, long total, int page, int size) {
    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        if (total < 0) throw new IllegalArgumentException("Total must not be negative");
        if (page < 1) throw new IllegalArgumentException("Page must be greater than or equal to 1");
        if (size < 1) throw new IllegalArgumentException("Size must be greater than or equal to 1");
    }

    public int totalPages() {
        return total == 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
