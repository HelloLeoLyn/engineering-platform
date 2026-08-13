package com.engineeringplatform.core.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class PageResultTest {
    @Test void calculatesTotalPages() { assertEquals(2, new PageResult<>(List.of("a", "b"), 21, 1, 20).totalPages()); }
    @Test void zeroTotalHasZeroPages() { assertEquals(0, new PageResult<String>(List.of(), 0, 1, 20).totalPages()); }
}
