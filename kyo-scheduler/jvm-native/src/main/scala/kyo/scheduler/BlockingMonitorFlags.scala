package kyo.scheduler

import kyo.StaticFlag

private[kyo] object blockingMonitorIntervalNs     extends StaticFlag[Int](2000000)
private[kyo] object blockingMonitorMinIntervalNs  extends StaticFlag[Int](-1)
private[kyo] object blockingMonitorBlockThreshold extends StaticFlag[Int](2)
