package io.agenticsdlc.core.insights;

import java.time.LocalDate;

/** How many runs were created on a UTC date. */
public record DailyCount(LocalDate date, long count) {
}
