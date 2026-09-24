package kyo.ffi.it

import java.nio.file.Files
import kyo.ffi.Ffi

class ItAbsentBindingsTest extends Test:

    // One process-wide failure, shared by both leaves: the override must be in place before the first load, and a failed load is final.
    private val notALibrary = Files.createTempFile("kyo-it-absent-", ".txt")
    Files.writeString(notALibrary, "not a shared library")
    java.lang.System.setProperty("kyo.ffi.kyo_it_absent.path", notALibrary.toString): Unit

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
            s"expected the name of $notALibrary (override now ${sys.props.get("kyo.ffi.kyo_it_absent.path")}) in the failure chain: " +
                chain.map(t => s"${t.getClass.getName}: ${t.getMessage}").mkString(" <- ")
        )
    }

    "a second Ffi.load of the same binding rethrows the first failure" in {
        val first  = loadFailure()
        val second = loadFailure()
        assert(second eq first)
    }

end ItAbsentBindingsTest
