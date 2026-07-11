package kyo.stats.machine

import kyo.*

/** The Linux host reader. A later phase wires the real `/proc` and `/sys/fs/cgroup` reads through
  * `MachineSampler.readScoped`; until then this reader returns an all-`Absent` reading, the same
  * graceful-degradation contract `NullMachine` provides for an OS with no dedicated reader.
  */
private[machine] object MachineLinux extends Machine:
    def read(sampler: MachineSampler)(using AllowUnsafe): Machine.Reading = Machine.Reading.empty
end MachineLinux
