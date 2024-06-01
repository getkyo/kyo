package kyo

import java.io.File
import kyo.internal.Trace

object jvm:
    /** Executes a class with given args for the JVM.
      */
    def spawn(klass: Class[?], args: List[String] = Nil)(using Trace): Process < IOs =
        process(klass, args).spawn
    end spawn

    /** Returns a `ProcessCommand` representing the execution of the `klass` Class in a new JVM process. To finally execute the command, use
      * `spawn` or use directly `jvm.spawn`.
      */
    def process(klass: Class[?], args: List[String] = Nil): ProcessCommand =
        val javaHome  = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val classPath = System.getProperty("java.class.path")
        val command   = javaHome :: "-cp" :: classPath :: klass.getName().init :: args

        ProcessCommand(command*)
    end process

end jvm
