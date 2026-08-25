package kyo.internal

import kyo.*
import kyo.db.Backend
import kyo.db.Idiom

/** Contract tests for the JS and Wasm half of [[kyo.db.Backend]]'s companion: the [[kyo.db.Backend.register]] door.
  *
  * On these two platforms `register` is the whole of runtime discovery, since neither reads a services file when the program runs. So each
  * leaf registers and then reads the backend back out of the discovery path, which is what makes the registration observable rather than
  * merely completed.
  */
class BackendPlatformSpecificTest extends Test:

    private object StubIdiom extends Idiom:
        def id: Idiom.Id                         = Idiom.Id("stub")
        def capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(1, 0, 0)
        def quoteIdent(ident: String): String    = "\"" + ident + "\""
        def placeholder(position: Int): String   = "?"
    end StubIdiom

    final private class StubBackend(canonical: String) extends Backend:
        def scheme: String       = canonical
        def aliases: Set[String] = Set.empty
        def dialect: Idiom       = StubIdiom
        def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
            Abort.panic[SqlException](new UnsupportedOperationException("the stub backend opens nothing"))
    end StubBackend

    /** The schemes a discovery pass finds right now.
      *
      * Reads `ServiceLoader` directly, the same call [[SqlBackendDiscovery]] makes, rather than
      * [[SqlBackendDiscovery.factories]]: that one memoizes its scan in a `lazy val`, so whether a registration made here falls inside the
      * memo would depend on whether some earlier suite already forced it. The shim rebuilds its provider list from the registry on every
      * `load`, which is what makes the read back deterministic.
      */
    private def discoveredSchemes: Set[String] =
        @scala.annotation.tailrec
        def loop(it: java.util.Iterator[Backend], acc: Set[String]): Set[String] =
            if !it.hasNext then acc
            else loop(it, acc + it.next().scheme)
        loop(java.util.ServiceLoader.load(classOf[Backend]).iterator(), Set.empty)
    end discoveredSchemes

    "register accepts a backend for runtime discovery".timeout(10.seconds) in {
        Backend.register(new StubBackend("stub-register-a"))
        val schemes = discoveredSchemes
        assert(schemes.contains("stub-register-a"), s"the registered backend must be discoverable, found $schemes")
    }

    "register accepts several backends in one program".timeout(10.seconds) in {
        Backend.register(new StubBackend("stub-register-b"))
        Backend.register(new StubBackend("stub-register-c"))
        val schemes = discoveredSchemes
        assert(schemes.contains("stub-register-b"), s"the first of the two registrations must be discoverable, found $schemes")
        assert(schemes.contains("stub-register-c"), s"the second of the two registrations must be discoverable, found $schemes")
    }

end BackendPlatformSpecificTest
