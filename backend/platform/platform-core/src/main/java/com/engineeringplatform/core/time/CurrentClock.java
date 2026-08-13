package com.engineeringplatform.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public interface CurrentClock {
    Instant instant();
    ZoneId zoneId();
    default LocalDate today() { return LocalDate.now(Clock.fixed(instant(), zoneId())); }
    default LocalDateTime now() { return LocalDateTime.ofInstant(instant(), zoneId()); }
}
