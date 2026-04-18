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
private[kyo] object concurrencyCollectWindow        extends StaticFlag[Int](200)
private[kyo] object concurrencyCollectIntervalMs    extends StaticFlag[Int](10)
private[kyo] object concurrencyRegulateIntervalMs   extends StaticFlag[Int](1500)
private[kyo] object concurrencyJitterUpperThreshold extends StaticFlag[Int](800000)
private[kyo] object concurrencyJitterLowerThreshold extends StaticFlag[Int](500000)
private[kyo] object concurrencyLoadAvgTarget        extends StaticFlag[Double](0.8)
private[kyo] object concurrencyStepExp              extends StaticFlag[Double](1.2)
