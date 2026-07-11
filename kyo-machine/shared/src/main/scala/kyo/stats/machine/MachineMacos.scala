package kyo.stats.machine

import kyo.*

/** The macOS host reader. Returns an all-`Absent` reading, the same graceful-degradation contract
  * `NullMachine` provides for an OS with no dedicated reader.
  */
private[machine] object MachineMacos extends Machine:
    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading = Machine.Reading.empty
end MachineMacos
