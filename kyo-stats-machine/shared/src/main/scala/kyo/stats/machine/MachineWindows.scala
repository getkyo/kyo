package kyo.stats.machine

import kyo.*

/** The Windows host reader. Returns an all-`Absent` reading, the same graceful-degradation contract
  * `NullMachine` provides for an OS with no dedicated reader.
  */
private[machine] object MachineWindows extends Machine:
    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading = Machine.Reading.empty
end MachineWindows
