package kyo.stats.machine

import kyo.*

/** The Linux proc-file decoders. Each reads primitives in place out of the sampler's retained buffer and
  * writes them straight into the retained cells: no `String`, no `split`, no collection, no carrier, and no
  * boxed absent value. Every field is total: a malformed, truncated, missing or non-numeric value decodes
  * to the primitive sentinel, which the cell skips, so it is never recorded and never registered.
  */
private[machine] object LinuxDecoders:

    private val CpuLine   = LinuxScan.ascii("cpu ")
    private val MemTotal  = LinuxScan.ascii("MemTotal:")
    private val MemAvail  = LinuxScan.ascii("MemAvailable:")
    private val MemFree   = LinuxScan.ascii("MemFree:")
    private val SwapTotal = LinuxScan.ascii("SwapTotal:")
    private val SwapFree  = LinuxScan.ascii("SwapFree:")

    /** `/proc/stat`'s aggregate cpu line: `cpu user nice system idle iowait irq softirq steal ...`, in
      * jiffies, each column scaled to nanoseconds. An older kernel with fewer columns contributes only the
      * columns it exposes, because a missing column decodes to the sentinel and the sentinel-aware sum
      * skips it.
      *
      * The kernel folds guest into user and guest_nice into nice, so neither is summed again. The reported
      * system mode includes irq and softirq (kernel-mode servicing time, the common convention). The total
      * sums every present column, so a `total - idle` derivation on a virtualized host is not
      * under-reported by omitted irq, softirq or steal time. Steal is ALSO emitted on its own, as the
      * hypervisor-contention signal a cloud host needs; it has no macOS or Windows equivalent, so that cell
      * is never written there and the series is never registered there.
      */
    def cpu(bytes: Span[Byte], len: Int, scale: Long, h: MachineHandles)(using AllowUnsafe): Unit =
        val from = LinuxScan.lineFields(bytes, len, CpuLine)
        if from >= 0 then
            val user    = LinuxScan.longField(bytes, len, from, 0, scale)
            val nice    = LinuxScan.longField(bytes, len, from, 1, scale)
            val system  = LinuxScan.longField(bytes, len, from, 2, scale)
            val idle    = LinuxScan.longField(bytes, len, from, 3, scale)
            val iowait  = LinuxScan.longField(bytes, len, from, 4, scale)
            val irq     = LinuxScan.longField(bytes, len, from, 5, scale)
            val softirq = LinuxScan.longField(bytes, len, from, 6, scale)
            val steal   = LinuxScan.longField(bytes, len, from, 7, scale)
            h.cpuUser.observe(user)
            h.cpuSystem.observe(LinuxScan.plus(LinuxScan.plus(system, irq), softirq))
            h.cpuIdle.observe(idle)
            h.cpuIowait.observe(iowait)
            h.cpuSteal.observe(steal)
            h.cpuTotal.observe(
                LinuxScan.plus(
                    LinuxScan.plus(
                        LinuxScan.plus(LinuxScan.plus(LinuxScan.plus(user, nice), system), idle),
                        LinuxScan.plus(iowait, irq)
                    ),
                    LinuxScan.plus(softirq, steal)
                )
            )
        end if
    end cpu

    /** `/proc/meminfo`, read and decoded ONCE per tick for BOTH the memory and the swap rows. Each
      * `Name:  <n> kB` value is scaled to bytes.
      */
    def meminfo(bytes: Span[Byte], len: Int, h: MachineHandles)(using AllowUnsafe): Unit =
        h.memTotal.set(LinuxScan.keyedLong(bytes, len, MemTotal, 0, 1024L))
        h.memAvailable.observe(LinuxScan.keyedLong(bytes, len, MemAvail, 0, 1024L))
        h.memFree.observe(LinuxScan.keyedLong(bytes, len, MemFree, 0, 1024L))
        h.swapTotal.set(LinuxScan.keyedLong(bytes, len, SwapTotal, 0, 1024L))
        h.swapFree.observe(LinuxScan.keyedLong(bytes, len, SwapFree, 0, 1024L))
    end meminfo

    /** `/proc/loadavg`: `<one> <five> <fifteen> ...`, three pre-averaged kernel averages. */
    def load(bytes: Span[Byte], len: Int, h: MachineHandles)(using AllowUnsafe): Unit =
        h.loadOne.set(LinuxScan.doubleField(bytes, len, 0, 0))
        h.loadFive.set(LinuxScan.doubleField(bytes, len, 0, 1))
        h.loadFifteen.set(LinuxScan.doubleField(bytes, len, 0, 2))
    end load

end LinuxDecoders
