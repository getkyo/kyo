package kyo.scheduler.regulator

import kyo.StaticFlag

// Admission flags
private[kyo] object rotationWindowMinutes         extends StaticFlag[Int](60)
private[kyo] object admissionCollectWindow        extends StaticFlag[Int](40)
private[kyo] object admissionCollectIntervalMs    extends StaticFlag[Int](100)
private[kyo] object admissionRegulateIntervalMs   extends StaticFlag[Int](1000)
private[kyo] object admissionJitterUpperThreshold extends StaticFlag[Int](100)
private[kyo] object admissionJitterLowerThreshold extends StaticFlag[Int](80)
private[kyo] object admissionLoadAvgTarget        extends StaticFlag[Double](0.8)
private[kyo] object admissionStepExp              extends StaticFlag[Double](1.5)

// Concurrency flags
private[kyo] object concurrencyCollectWindow      extends StaticFlag[Int](200)
private[kyo] object concurrencyCollectIntervalMs  extends StaticFlag[Int](10)
private[kyo] object concurrencyRegulateIntervalMs extends StaticFlag[Int](1500)
// The jitter band is denominated in the platform's sleep-probe noise. POSIX hosts wake sleep(1)
// within tens of microseconds, so their idle probe stddev sits well under the 0.5ms grow
// threshold. Windows quantizes sleep wake-ups between 1ms and 2ms, an idle floor of roughly
// 0.7ms stddev that sits inside the POSIX band and would freeze regulation (never below the
// grow threshold, never above the shrink one), so its band is sized above that floor.
private[kyo] object isWindowsHost {
    val value: Boolean = java.lang.System.getProperty("os.name", "").toLowerCase.contains("win")
}
private[kyo] object concurrencyJitterUpperThreshold extends StaticFlag[Int](if (isWindowsHost.value) 2500000 else 800000)
private[kyo] object concurrencyJitterLowerThreshold extends StaticFlag[Int](if (isWindowsHost.value) 1500000 else 500000)
private[kyo] object concurrencyLoadAvgTarget        extends StaticFlag[Double](0.8)
private[kyo] object concurrencyStepExp              extends StaticFlag[Double](1.2)
