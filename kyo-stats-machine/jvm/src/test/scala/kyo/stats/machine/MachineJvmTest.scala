package kyo.stats.machine

import kyo.*

class MachineJvmTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    /** Locates the `kyo-stats-machine` module root by walking up from the forked JVM working directory,
      * mirroring `MachineStatFactoryTest`'s established repo-relative lookup.
      */
    private def locateModuleRoot()(using kyo.test.AssertScope): java.io.File =
        val name = "kyo-stats-machine"
        Iterator.iterate(new java.io.File(".").getCanonicalFile)(_.getParentFile)
            .take(6)
            .map(root => new java.io.File(root, name))
            .find(_.isDirectory)
            .getOrElse(fail(s"could not locate the $name module root; run tests from the repository root or a subproject directory"))
    end locateModuleRoot

    private def readingFreeSourceFiles(moduleRoot: java.io.File): List[java.io.File] =
        List("Machine.scala", "MachineLinux.scala", "MachineMacos.scala", "MachineWindows.scala")
            .map(name => new java.io.File(moduleRoot, s"shared/src/main/scala/kyo/stats/machine/$name"))

    private val readingClassPattern = "case class \\w*Reading".r

    "no reading class exists and forOs constructs per-OS reader instances".onlyJvm in {
        val moduleRoot = locateModuleRoot()
        val files      = readingFreeSourceFiles(moduleRoot)
        assert(files.forall(_.isFile))
        val matches = files.flatMap { f =>
            val content = new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
            readingClassPattern.findAllIn(content).toList
        }
        for handles <- MachineHandles.init
        yield
            val sampler = new MachineSampler(handles)
            assert(matches.isEmpty)
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

end MachineJvmTest
