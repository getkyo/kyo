package kyo.stats.machine

import kyo.*

/** cgroup v1 and v2 detection, process-cgroup resolution, and the per-tick read.
  *
  * The layout is resolved ONCE, at construction: which hierarchy is mounted, and where this process's
  * cgroup sits inside it. That does not change for the process lifetime. Each resource file then gets ONE
  * retained read handle, and the tick is a sequence of primitive reads straight into the cells.
  *
  * Unit scaling stores nanoseconds: v2 microsecond fields are scaled by 1000, v1 nanosecond fields are
  * not. An unset v2 limit or quota is the literal `max`, which the parser reads as absent because it has no
  * leading digit; an unset v1 quota is -1, which is absent for the same reason (the parser accepts no
  * sign); the v1 unlimited-memory marker is a value at or above 2^62 and routes to absent explicitly.
  */
final private[machine] class LinuxCgroup(h: MachineHandles, s: MachineSampler)(using AllowUnsafe):

    import LinuxCgroup.*

    // The cgroup filesystem mount points come from /proc/self/mountinfo, not a hardcoded path: this resolves a
    // cgroup2 mount at a non-conventional point and a v1 controller mounted at a path that does not match its
    // controller name, reconciled with /proc/self/cgroup for THIS process's cgroup-relative path. The
    // conventional /sys/fs/cgroup is the last-resort fallback, used only when mountinfo carries no cgroup
    // mount. Resolved ONCE, at construction; the layout does not change for the process lifetime.
    private val root   = resolveV2Mount().getOrElse(FallbackRoot)
    private val v2     = Path(root + "/cgroup.controllers").unsafe.exists()
    private val dir    = if v2 then resolveV2Dir() else root
    private val v1Dirs = if v2 then Map.empty[String, String] else resolveV1Dirs()

    private val memDir = if v2 then dir else v1Dirs.getOrElse("memory", root + "/memory")

    // v1Dirs keys by INDIVIDUAL controller ("cpu", "cpuacct"), never by the compound "cpu,cpuacct" mount
    // name, so either individual key resolves and the compound mount root is the fallback.
    private val cpuDir =
        if v2 then dir
        else v1Dirs.get("cpu").orElse(v1Dirs.get("cpuacct")).getOrElse(root + "/cpu,cpuacct")

    /** The resolved cgroup directory, and whether it carries the PSI files. Read by the pressure reader. */
    val v2Dir: String          = dir
    val hasV2Pressure: Boolean = v2 && Path(dir + "/cpu.pressure").unsafe.exists()

    private val usageSlot =
        s.openSlot(Path(if v2 then memDir + "/memory.current" else memDir + "/memory.usage_in_bytes"))
    private val limitSlot =
        s.openSlot(Path(if v2 then memDir + "/memory.max" else memDir + "/memory.limit_in_bytes"))
    private val quotaSlot =
        s.openSlot(Path(if v2 then cpuDir + "/cpu.max" else cpuDir + "/cpu.cfs_quota_us"))
    private val periodSlot =
        if v2 then Absent else s.openSlot(Path(cpuDir + "/cpu.cfs_period_us"))
    private val statSlot = s.openSlot(Path(cpuDir + "/cpu.stat"))

    private val throttledKey   = if v2 then ThrottledUsec else ThrottledTime
    private val throttledScale = if v2 then 1000L else 1L

    /** v2 `cpu.max` carries BOTH the quota and the period on one line, so one read decodes both. */
    private val decodeCpuMax: MachineSampler.Decode = new MachineSampler.Decode:
        def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit =
            h.cgCpuQuota.set(LinuxScan.longField(b, n, 0, 0, 1000L))
            h.cgCpuPeriod.set(LinuxScan.longField(b, n, 0, 1, 1000L))

    /** `cpu.stat` carries all three throttling fields, so one read decodes all three. */
    private val decodeCpuStat: MachineSampler.Decode = new MachineSampler.Decode:
        def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit =
            h.cgCpuPeriods.observe(LinuxScan.keyedLong(b, n, NrPeriods, 0, 1L))
            h.cgThrPeriods.observe(LinuxScan.keyedLong(b, n, NrThrottled, 0, 1L))
            h.cgThrTime.observe(LinuxScan.keyedLong(b, n, throttledKey, 0, throttledScale))
        end apply

    def read()(using AllowUnsafe): Unit =
        h.cgMemUsage.observe(s.readLongFrom(usageSlot))
        h.cgMemLimit.set(limit(s.readLongFrom(limitSlot)))
        if v2 then discard(s.readInto(quotaSlot, decodeCpuMax))
        else
            h.cgCpuQuota.set(LinuxScan.scaled(s.readLongFrom(quotaSlot), 1000L))
            h.cgCpuPeriod.set(LinuxScan.scaled(s.readLongFrom(periodSlot), 1000L))
        end if
        discard(s.readInto(statSlot, decodeCpuStat))
    end read

    /** The v1 unlimited-memory marker is not a limit: it routes to absent, so no fabricated ceiling is
      * ever exported. Package-private and pure so the routing is testable without staging a file read.
      */
    private[machine] def limit(v: Long): Long =
        if v == Path.ReadHandle.AbsentLong || v >= UnlimitedSentinel then Path.ReadHandle.AbsentLong else v

    /** The cgroup2 mount point from `/proc/self/mountinfo`, absent when no cgroup2 filesystem is mounted, so
      * the conventional fallback root stands in.
      */
    private def resolveV2Mount(): Maybe[String] =
        s.readOnce(Path("/proc/self/mountinfo"), (b, n) => LinuxCgroupPath.mountRootV2(b, n)).flatten

    private def resolveV2Dir(): String =
        s.readOnce(Path("/proc/self/cgroup"), (b, n) => LinuxCgroupPath.v2Dir(b, n, root))
            .getOrElse(root)

    /** Joins each v1 controller's real mountinfo mount point with this process's cgroup-relative path. When
      * mountinfo carries no v1 cgroup mount, the conventional `<root>/<controllers><path>` layout stands in.
      */
    private def resolveV1Dirs(): Map[String, String] =
        val mountRoots =
            s.readOnce(Path("/proc/self/mountinfo"), (b, n) => LinuxCgroupPath.v1MountRoots(b, n)).getOrElse(Map.empty)
        if mountRoots.isEmpty then
            s.readOnce(Path("/proc/self/cgroup"), (b, n) => LinuxCgroupPath.v1Dirs(b, n, root)).getOrElse(Map.empty)
        else
            val rels = s.readOnce(Path("/proc/self/cgroup"), (b, n) => LinuxCgroupPath.v1Rel(b, n)).getOrElse(Map.empty)
            LinuxCgroupPath.reconcileV1(mountRoots, rels, root)
        end if
    end resolveV1Dirs

end LinuxCgroup

private[machine] object LinuxCgroup:
    val FallbackRoot      = "/sys/fs/cgroup"
    val UnlimitedSentinel = 1L << 62

    private[machine] val NrPeriods     = LinuxScan.ascii("nr_periods")
    private[machine] val NrThrottled   = LinuxScan.ascii("nr_throttled")
    private[machine] val ThrottledUsec = LinuxScan.ascii("throttled_usec")
    private[machine] val ThrottledTime = LinuxScan.ascii("throttled_time")
end LinuxCgroup
