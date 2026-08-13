package com.engineeringplatform.core.paging;

import java.util.List;

public record PageQuery(int page, int size, List<SortSpec> sorts) {
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    public PageQuery {
        if (page < 1) throw new IllegalArgumentException("Page must be greater than or equal to 1");
        if (size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("Page size must be between 1 and " + MAX_SIZE);
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
    }

    public static PageQuery defaultQuery() { return new PageQuery(DEFAULT_PAGE, DEFAULT_SIZE, List.of()); }
    public long offset() { return (long) (page - 1) * size; }
}
