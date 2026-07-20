package kyo.internal

import kyo.*

/** Windows diagnosis probe (dev artifact, probe branch only, never merged).
  *
  * Discriminates the root cause of the Windows CI failures:
  *   - A: worker-path compile yields empty diagnostics (CompilerWorkerTest, spawn parity)
  *   - B: process still alive immediately after destroyForcibly
  */
class WinProbeTest extends kyo.test.Test[Any]:

    override def timeout = 300.seconds

    private def findJar(fragment: String): String =
        java.lang.System.getProperty("java.class.path", "")
            .split(Path.pathSeparator.charAt(0))
            .find(_.contains(fragment))
            .getOrElse(throw new RuntimeException(s"Jar containing '$fragment' not found on java.class.path"))

    private def directConfig(): Compiler.Config =
        val pcClasspath = Seq(findJar("scala3-library"), findJar("scala-library"))
        Compiler.Config(
            toolchain = Compiler.Toolchain(
                scalaVersion = "3.8.4",
                compilerClasspath = Chunk.from(pcClasspath.map(Path(_)))
            ),
            classpath = Chunk.from(pcClasspath.map(Path(_))),
            scalacOptions = Chunk.empty,
            sourceRoots = Chunk.empty
        )
    end directConfig

    private def show(tag: String, c: Compiler.Config): Unit =
        println(s"[probe] $tag scalaVersion=${c.toolchain.scalaVersion}")
        println(s"[probe] $tag classpath      = ${c.classpath.map(_.toString).toList}")
        println(s"[probe] $tag compilerCp     = ${c.toolchain.compilerClasspath.map(_.toString).toList}")
        println(
            s"[probe] $tag scalacOptions  = ${c.scalacOptions.toList} sourceRoots=${c.sourceRoots.map(_.toString).toList} isolate=${c.isolate}"
        )
        c.classpath.foreach { p =>
            val nio = java.nio.file.Paths.get(p.toString)
            println(s"[probe] $tag entry '${p.toString}' -> nio='$nio' exists=${java.nio.file.Files.exists(nio)}")
        }
    end show

    private def compileOnce(tag: String, config: Compiler.Config, uriName: String, text: String) =
        for
            backend <- LocalBackend.init(config)
            resp    <- Abort.run[CompilerException](backend.run(Request.Compile(Compiler.Uri(uriName), text)))
            _ = println(s"[probe] $tag compile('$text') -> $resp")
            _ <- Abort.run[Throwable](backend.close)
        yield resp

    "A: direct vs property-roundtrip config, same buffer" in {
        val pcClasspath = Seq(findJar("scala3-library"), findJar("scala-library")).mkString(Path.pathSeparator)
        println(s"[probe] java.class.path head = ${java.lang.System.getProperty("java.class.path", "").take(400)}")
        println(s"[probe] pathSeparator='${Path.pathSeparator}' raw pcClasspath=$pcClasspath")
        java.lang.System.setProperty("kyo.internal.WorkerFlags.scalaVersion", "3.8.4")
        java.lang.System.setProperty("kyo.internal.WorkerFlags.classpath", pcClasspath)
        java.lang.System.setProperty("kyo.internal.WorkerFlags.options", "")
        for
            roundtrip <- WorkerConfig.fromEnv()
            direct = directConfig()
            _      = show("roundtrip", roundtrip)
            _      = show("direct   ", direct)
            _      = println(s"[probe] classpath equal = ${roundtrip.classpath.map(_.toString) == direct.classpath.map(_.toString)}")
            r1 <- compileOnce("roundtrip", roundtrip, "Worker.scala", "val x: Int = \"not an int\"")
            r2 <- compileOnce("direct   ", direct, "Main.scala", "val x: Int = \"not an int\"")
            r3 <- compileOnce("roundtrip2", roundtrip, "Worker2.scala", "object M { val i: Int = \"not an int\" }")
        yield assert(true)
        end for
    }

    "B: destroyForcibly then waitFor then isAlive timeline (the exact kill-test sequence)" in {
        val javaBin = Path(java.lang.System.getProperty("java.home"), "bin", "java").toString
        for
            sleeper <- Command(javaBin, "-cp", java.lang.System.getProperty("java.class.path", ""), "kyo.internal.WinProbeSleeper")
                .inheritStderr.spawnUnscoped
            _  <- Async.sleep(2.seconds)
            a0 <- sleeper.isAlive
            _ = println(s"[probe] sleeper alive before kill = $a0")
            _    <- sleeper.destroyForcibly
            code <- sleeper.waitFor
            a1   <- sleeper.isAlive
            _ = println(s"[probe] waitFor -> $code; isAlive right after waitFor = $a1")
            _  <- Async.sleep(50.millis)
            a2 <- sleeper.isAlive
            _  <- Async.sleep(500.millis)
            a3 <- sleeper.isAlive
            _ = println(s"[probe] alive at t=50ms = $a2, t=550ms = $a3")
        yield assert(true)
        end for
    }

end WinProbeTest

object WinProbeSleeper:
    def main(args: Array[String]): Unit =
        Thread.sleep(600000)
end WinProbeSleeper
