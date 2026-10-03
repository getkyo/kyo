package kyo.stats.machine

import kyo.*

class MachineJvmTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "forOs constructs per-OS reader instances".onlyJvm in {
        for handles <- MachineHandlesOwners.init
        yield
            val sampler = new MachineSampler(handles)
            assert(Machine.forOs(System.OS.Linux, handles, sampler).isInstanceOf[MachineLinux])
            assert(Machine.forOs(System.OS.MacOS, handles, sampler).isInstanceOf[MachineMacos])
            assert(Machine.forOs(System.OS.Windows, handles, sampler).isInstanceOf[MachineWindows])
            assert(Machine.forOs(System.OS.BSD, handles, sampler) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.Solaris, handles, sampler) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.IBMI, handles, sampler) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.AIX, handles, sampler) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.Unknown, handles, sampler) eq Machine.NullMachine)
        end for
    }

    "a tick never parks or blocks the thread running it".onlyJvm in {
        // A tick runs on a scheduler carrier thread every second. The JVM counts every time a thread waits
        // (sleep, park, Object.wait) or blocks on a contended monitor, so both counts stay flat across ticks
        // of the host's real reader exactly when the tick has no blocking construct on its path.
        val hostOs = System.live.unsafe.operatingSystem()
        assume(
            hostOs == System.OS.Linux || hostOs == System.OS.MacOS || hostOs == System.OS.Windows,
            "this leaf drives the host's real reader and needs a host OS with a dedicated Machine implementation"
        )
        val handles               = MachineHandlesOwners.initForTest(Stat.initScope("mjtest-blocking"), 8L)
        val sampler               = new MachineSampler(handles)
        val machine               = Machine.forOs(hostOs, handles, sampler)
        val threads               = java.lang.management.ManagementFactory.getThreadMXBean
        val id                    = Thread.currentThread().threadId()
        def waits(): (Long, Long) =
            val info = threads.getThreadInfo(id)
            (info.getWaitedCount, info.getBlockedCount)
        def tick(): Unit =
            machine.read()
            machine.readDisks()
        try
            // The first tick loads and initializes the reader's classes, whose initialization locks are not the
            // reader's own construct.
            tick()
            val before = waits()
            (1 to 200).foreach(_ => tick())
            val after = waits()
            assert(after == before, s"(waited, blocked) counts moved from $before to $after across 200 ticks")
        finally machine.close()
        end try
    }

end MachineJvmTest
