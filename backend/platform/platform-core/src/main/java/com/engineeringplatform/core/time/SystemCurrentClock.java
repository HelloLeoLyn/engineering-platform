package com.engineeringplatform.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

public final class SystemCurrentClock implements CurrentClock {
    private final Clock clock;
    public SystemCurrentClock(Clock clock) { this.clock = clock; }
    public static SystemCurrentClock systemDefaultZone() { return new SystemCurrentClock(Clock.systemDefaultZone()); }
    public static SystemCurrentClock systemUtc() { return new SystemCurrentClock(Clock.systemUTC()); }
    @Override public Instant instant() { return clock.instant(); }
    @Override public ZoneId zoneId() { return clock.getZone(); }
}
