package selfgemma.talk.domain.roleplay.usecase

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSystemTimeSnapshotAndroidTest {
  @Test
  fun capture_formatsKnownChineseNewYearDate() {
    val snapshot =
      DeviceSystemTimeSnapshot.capture(
        now = ZonedDateTime.of(2024, 2, 10, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai")),
      )

    assertEquals("2024-02-10", snapshot.gregorianDate)
    assertEquals("正月初一", snapshot.lunarDate)
    assertEquals("09:30", snapshot.time24h)
    assertEquals("Asia/Shanghai", snapshot.timeZoneId)
  }

  @Test
  fun capture_formatsKnownMidAutumnDate() {
    val snapshot =
      DeviceSystemTimeSnapshot.capture(
        now = ZonedDateTime.of(2024, 9, 17, 21, 15, 0, 0, ZoneId.of("Asia/Shanghai")),
      )

    assertEquals("八月十五", snapshot.lunarDate)
  }
}
