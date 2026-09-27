package kyo.ffi.it

import java.nio.file.Files
import kyo.discard
import kyo.ffi.Ffi

class ItAbsentBindingsTest extends Test:

    // Read at construction, which puts the override in place before the first load.
    private val notALibrary = ItAbsentBindingsTest.notALibrary

    private def loadFailure()(using kyo.test.AssertScope): Throwable =
        val failure =
            try
                Ffi.load[ItAbsentBindings]
                kyo.Absent
            catch case e: Throwable => kyo.Present(e)
        failure.getOrElse(fail("Ffi.load returned a binding whose library does not open"))
    end loadFailure

    "Ffi.load of a binding whose library does not open fails the load itself, with the loader's exception" in {
        val failure = loadFailure()
        val chain   = Iterator.iterate(failure)(_.getCause).takeWhile(_ ne null).toList
        assert(!failure.isInstanceOf[java.lang.reflect.InvocationTargetException])
        assert(!failure.isInstanceOf[ExceptionInInitializerError])
        assert(
            chain.exists(t => String.valueOf(t.getMessage).contains(notALibrary.getFileName.toString)),
            s"expected the name of $notALibrary in the failure chain: " +
                chain.map(t => s"${t.getClass.getName}: ${t.getMessage}").mkString(" <- ")
        )
    }

    "a second Ffi.load of the same binding rethrows the first failure" in {
        val first  = loadFailure()
        val second = loadFailure()
        assert(second eq first)
    }

end ItAbsentBindingsTest

object ItAbsentBindingsTest:

    /** The file the binding's path override names, one per process.
      *
      * The loader keeps a failed load for the life of the process, and the failure names the override in force at the first load. The
      * runner instantiates a suite more than once, so an instance-level file would give each instance a different name while the
      * failure keeps naming whichever instance's override the first load saw.
      */
    private val notALibrary =
        val path = Files.createTempFile("kyo-it-absent-", ".txt")
        discard(Files.writeString(path, "not a shared library"))
        discard(java.lang.System.setProperty("kyo.ffi.kyo_it_absent.path", path.toString))
        path
    end notALibrary
end ItAbsentBindingsTest
