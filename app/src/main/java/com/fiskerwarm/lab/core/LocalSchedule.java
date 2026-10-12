package com.fiskerwarm.lab.core;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** Calendar calculation only. No Android alarm or vehicle side effect. */
public final class LocalSchedule {
    private LocalSchedule() {}
    public static Instant next(Instant after, ZoneId zone, LocalTime time, Set<DayOfWeek> days) {
        if (days==null || days.isEmpty()) throw new IllegalArgumentException("No weekdays selected");
        LocalDate start=after.atZone(zone).toLocalDate();
        Instant best=null;
        for(int i=0;i<=8;i++) {
            LocalDate day=start.plusDays(i);
            if (!days.contains(day.getDayOfWeek())) continue;
            // In a DST gap skip that occurrence. In an overlap run at most once:
            // select the earlier offset and never catch up on a missed occurrence.
            java.util.List<java.time.ZoneOffset> offsets=zone.getRules().getValidOffsets(day.atTime(time));
            if (offsets.isEmpty()) continue;
            ZonedDateTime date=ZonedDateTime.ofLocal(day.atTime(time),zone,offsets.get(0));
            Instant value=date.toInstant();
            if(value.isAfter(after) && (best==null || value.isBefore(best))) best=value;
        }
        if(best==null) throw new IllegalStateException("No next occurrence");
        return best;
    }
    public static Instant once(Instant start, Instant now) {
        if (!start.isAfter(now)) throw new IllegalArgumentException("Past one-shot time");
        return start;
    }
}
