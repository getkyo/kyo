package kyo.stats.machine

import kyo.*

class MachineTest extends kyo.test.Test[Any]:

    // MachineHandles.init resolves the SAME process-global StatsRegistry scope ("machine") every
    // leaf in this file and every other kyo-machine test file share, so a concurrently-running
    // leaf that also observes cpuTimeTotal/memTotal/loadOne would corrupt this leaf's before/after
    // delta assertions.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    "Machine.forOs" - {

        "maps Linux/MacOS/Windows to their impls and any other OS to the all-Absent NullMachine" in {
            assert(Machine.forOs(System.OS.Linux) eq MachineLinux)
            assert(Machine.forOs(System.OS.MacOS) eq MachineMacos)
            assert(Machine.forOs(System.OS.Windows) eq MachineWindows)
            assert(Machine.forOs(System.OS.BSD) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.Solaris) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.IBMI) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.AIX) eq Machine.NullMachine)
            assert(Machine.forOs(System.OS.Unknown) eq Machine.NullMachine)
        }
    }

    "NullMachine" - {

        "read returns an all-Absent reading, no throw" in {
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, Machine.NullMachine)
                reading = Machine.NullMachine.read(sampler)
            yield
                assert(reading.cpu.isEmpty)
                assert(reading.memory.isEmpty)
                assert(reading.swap.isEmpty)
                assert(reading.disks.isEmpty)
                assert(reading.load.isEmpty)
                assert(reading.cgroup.isEmpty)
                assert(reading.pressure.isEmpty)
                assert(reading.cgroupPressure.isEmpty)
                assert(reading.disks == Chunk.empty)
                assert(reading.cpu == Absent && reading.memory == Absent && reading.swap == Absent)
                assert(reading.load == Absent && reading.cgroup == Absent)
                assert(reading.pressure == Absent && reading.cgroupPressure == Absent)
        }
    }

    "an all-Absent reading" - {

        "records zero machine.* metrics for the tick" in {
            for
                handles <- MachineHandles.init
                memCountBefore  = handles.memTotal.unsafe.summary().count
                loadCountBefore = handles.loadOne.unsafe.summary().count
                sampler         = new MachineSampler(handles, Machine.NullMachine)
                _               = sampler.observe(Machine.Reading.empty)
                cpuDelta        = handles.cpuTimeTotal.unsafe.get()
                memCountAfter   = handles.memTotal.unsafe.summary().count
                loadCountAfter  = handles.loadOne.unsafe.summary().count
            yield
                assert(cpuDelta == 0L)
                assert(memCountAfter == memCountBefore)
                assert(loadCountAfter == loadCountBefore)
        }
    }

end MachineTest
