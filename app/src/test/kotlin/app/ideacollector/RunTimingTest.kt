package app.ideacollector

import app.ideacollector.work.*
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RunTimingTest {
    private val now = ZonedDateTime.parse("2026-11-24T10:00:00+09:00[Asia/Seoul]")
    private val time = LocalTime.of(7, 0)

    @Test fun nextRunUsesLocalDateAndTime() {
        assertEquals(now.toLocalDate().plusDays(1), nextRun(now, time).toLocalDate())
        assertEquals(now.toLocalDate(), nextRun(now, time.plusHours(4)).toLocalDate())
        assertNotEquals(nextRun(now, time).toInstant(), nextRun(now.withZoneSameInstant(ZoneOffset.UTC), time).toInstant())
    }

    @Test fun backupAndMissedCheckTimeAndTodayRecord() {
        assertEquals(now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.zone), nextBackup(now, time))
        assertEquals(now.toLocalDate(), nextBackup(now.minusHours(2), time).toLocalDate())
        val late = LocalTime.of(23, 0)
        val afterMidnight = now.plusDays(1).withHour(1).withMinute(15)
        assertEquals(now.toLocalDate().plusDays(1).atTime(1, 0).atZone(now.zone), nextBackup(now, late))
        assertEquals(now.toLocalDate(), backupRunDate(afterMidnight, late))
        assertTrue(runMissed(now, time, false))
        assertFalse(runMissed(now.minusMinutes(1), time, false))
        assertFalse(runMissed(now, time, true))
    }

    @Test fun onlyEnabledRunConditionsDeferWork() {
        assertTrue(conditionsMet(RunOptions(), wifi = false, charging = false))
        assertFalse(conditionsMet(RunOptions(wifiOnly = true), wifi = false, charging = true))
        assertFalse(conditionsMet(RunOptions(chargingOnly = true), wifi = true, charging = false))
        assertTrue(conditionsMet(RunOptions(wifiOnly = true, chargingOnly = true), wifi = true, charging = true))
    }

    @Test fun deferredBackupKeepsItsOriginalDateAfterMidnight() {
        val midnight = now.plusDays(1).withHour(1).withMinute(15)
        assertTrue(validBackupRunDate(midnight, now.toLocalDate(), LocalTime.of(23, 0)))
        assertFalse(validBackupRunDate(midnight, now.toLocalDate(), time))
        assertFalse(validBackupRunDate(midnight, now.toLocalDate().minusDays(1), LocalTime.of(23, 0)))
    }

    @Test fun deferredRunExpiresAtMidnight() {
        assertTrue(validRunDate(now, now.toLocalDate()))
        assertFalse(validRunDate(now.plusDays(1), now.toLocalDate()))
    }
}
