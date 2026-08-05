package kyo.internal
import kyo.*
import kyo.Test
import kyo.db.Backend
import kyo.db.Idiom
import kyo.internal.postgres.PostgresDialect

/** Pins that a backend is found at RUN time, and found by name.
  *
  * The compile-time half of backend resolution is covered by `SqlBackendRegistryTest` and the two `SqlClientInitMacro` suites. What this
  * suite covers is the tier those cannot reach: a scheme resolved through `SqlBackendDiscovery` rather than through the factories the
  * derivation spliced in.
  *
  * The assertions are on NAMES, and that is the point of the suite rather than a stylistic choice. Discovery failing is indistinguishable
  * from discovery succeeding over an empty set unless something asserts what was found: a backend that ships its
  * `META-INF/services/kyo.db.Backend` entry and forgets the JS and Wasm registration, or the Native link-time enlistment, produces an
  * empty result and no error at all, so every "did not fail" assertion would still pass. `sqlite` is used for the unclaimed scheme because no
  * artifact in this build declares it in either tier.
  *
  * A `Registry` built here with `Chunk.empty` is how a backend absent from the compile classpath is modelled: it is exactly the registry the
  * derivation produces when no backend artifact was present, so a resolution against it can only have come from run time.
  */
class SqlBackendDiscoveryTest extends Test:

    private val postgresFactory = "kyo.internal.postgres.PostgresBackendFactory"
    private val mysqlFactory    = "kyo.internal.mysql.MysqlBackendFactory"
    // The out-of-tree stub is register-only and shares this test program, so it may be in the discovered set. This
    // suite is about the SHIPPING backends, so it filters the stub out rather than pinning it, keeping the shipping
    // checks exact. `StubBackendTest` is what pins the stub.
    private val stubBackend = "com.example.stub.StubBackend"

    /** Claims `postgres` while declaring no alias, so a lookup answering it is distinguishable from `PostgresBackendFactory` by value rather
      * than only by class. Never opened: the suite asks who claims a scheme, not what happens after.
      */
    private class SchemeProbeFactory extends Backend:
        val scheme: String       = "postgres"
        val aliases: Set[String] = Set.empty
        val dialect: Idiom       = PostgresDialect

        // Public and unscoped, matching the single `open` on kyo.db.Backend. It fails rather than assembling a
        // client, since the suite never reaches it.
        def open(url: SqlConfig.Url, config: SqlConfig)(using
            Frame
        ): SqlClient < (Async & Abort[SqlException]) =
            Abort.fail(SqlConnectionUnsupportedSchemeException(url.address.scheme, Chunk.empty))
    end SchemeProbeFactory

    // The shipping backends reach discovery through the services scan on the JVM and, on Native where only one services
    // file is embedded, through register(); the stub reaches it through register() everywhere. That register side is set
    // up once here, before any leaf reads discovery.
    TestBackendRegistration.ensure()

    "discovery finds every backend this program declares, by name" in {
        val found    = SqlBackendDiscovery.factories.map(_.getClass.getName).toSeq
        val shipping = found.filterNot(_ == stubBackend).sorted
        assert(
            shipping == Seq(mysqlFactory, postgresFactory),
            s"runtime discovery found $found (shipping $shipping). An empty or short shipping list means a backend declared " +
                "in META-INF/services has no matching registration on this platform, which is silent everywhere else."
        )
    }

    "a registry with no compile-time factory resolves a canonical scheme through discovery" in {
        new Backend.Registry(Chunk.empty).forScheme("postgres") match
            case Present(factory) => assert(factory.getClass.getName == postgresFactory, s"resolved ${factory.getClass.getName}")
            case Absent           => fail("postgres is declared by an artifact this program depends on, so discovery must resolve it")
        new Backend.Registry(Chunk.empty).forScheme("mysql") match
            case Present(factory) => assert(factory.getClass.getName == mysqlFactory, s"resolved ${factory.getClass.getName}")
            case Absent           => fail("mysql is declared by an artifact this program depends on, so discovery must resolve it")
    }

    "a registry with no compile-time factory resolves a declared alias through discovery" in {
        new Backend.Registry(Chunk.empty).forScheme("postgresql") match
            case Present(factory) =>
                assert(factory.getClass.getName == postgresFactory, s"resolved ${factory.getClass.getName}")
                assert(factory.scheme == "postgres", s"the alias must resolve to the canonical scheme, got ${factory.scheme}")
            case Absent => fail("postgresql is a declared alias of postgres, so discovery must resolve it")
    }

    "a compile-time factory wins a scheme discovery also claims" in {
        val registry = new Backend.Registry(Chunk[Backend](new SchemeProbeFactory))
        registry.forScheme("postgres") match
            case Present(factory) =>
                assert(factory.aliases.isEmpty, s"the compile-time factory declares no alias, so a win shows as ${factory.aliases}")
                assert(factory.getClass.getName.endsWith("SchemeProbeFactory"), s"resolved ${factory.getClass.getName}")
            case Absent => fail("a compile-time factory claiming postgres must resolve it")
        end match
        // The discovered factory is not displaced for its OWN unclaimed schemes: it still answers the alias.
        registry.forScheme("postgresql") match
            case Present(factory) => assert(factory.getClass.getName == postgresFactory, s"resolved ${factory.getClass.getName}")
            case Absent           => fail("postgresql is claimed by no compile-time factory here, so discovery must answer it")
    }

    "schemes on a registry with no compile-time factory names what discovery can open" in {
        val schemes = new Backend.Registry(Chunk.empty).schemes.toSeq.filterNot(_ == "stub").sorted
        assert(schemes == Seq("mysql", "postgres", "postgresql"), s"schemes were $schemes")
    }

    "schemes lists the compile-time schemes first and adds only what discovery reaches" in {
        val schemes = new Backend.Registry(Chunk[Backend](new SchemeProbeFactory)).schemes
        assert(schemes.head == "postgres", s"the compile-time scheme must come first, schemes were ${schemes.toSeq}")
        assert(schemes.toSeq.filterNot(_ == "stub").sorted == Seq("mysql", "postgres", "postgresql"), s"schemes were ${schemes.toSeq}")
        assert(schemes.toSeq.count(_ == "postgres") == 1, s"a scheme claimed by both tiers must be listed once, got ${schemes.toSeq}")
    }

    "a scheme neither tier claims is Absent" in {
        assert(new Backend.Registry(Chunk.empty).forScheme("sqlite") == Absent)
        assert(new Backend.Registry(Chunk.empty).forScheme("") == Absent)
    }

    "the open path resolves through discovery when the compile-time registry is empty" in {
        // Built rather than written out, so the value is opaque to the init splice exactly as a computed URL is.
        val computed = "postgres" + "://u:p@localhost:5432/db"
        Abort.run[SqlException](SqlClient.factoryFor(computed, new Backend.Registry(Chunk.empty))).map {
            case Result.Success((url, factory)) =>
                assert(url.address.scheme == "postgres", s"parsed scheme was ${url.address.scheme}")
                assert(factory.getClass.getName == postgresFactory, s"resolved ${factory.getClass.getName}")
            case other =>
                fail(s"expected the open path to resolve postgres through discovery, got $other")
        }
    }

    // --- The J6 plugin-dispatch shape, observed rather than inferred ---
    //
    // The leaves above build an empty registry by hand, which models a backend-agnostic call site. These three
    // do not model it: `SqlBackendDiscoveryWrapper` LIVES in kyo-sql's test tree, where no backend is on the
    // compile classpath, so its `summon` spliced an empty registry at ITS compile time and this module cannot
    // add to it. Calling it from here, where both backends are present and (on Native) link-enlisted, is the
    // real cross-module arrangement: plugin compiled against core alone, host carrying the backend artifacts.
    //
    // Postgres and MySQL are asserted in SEPARATE leaves on purpose. Together in one leaf they cannot tell
    // "the runtime tier does not work on this platform" from "the runtime tier works and one artifact's
    // providers were discarded", and those have different causes and different fixes.

    "the wrapper module's own registry is empty, so anything it resolves came from discovery" in {
        val compiled = SqlBackendDiscoveryWrapper.registry.factories.map(_.getClass.getName).toSeq
        assert(
            compiled.isEmpty,
            s"the wrapper compiled against kyo-sql alone must have spliced an empty registry, got $compiled. " +
                "If this ever reports factories, the leaves below stop being about runtime discovery."
        )
    }

    "a cross-module wrapper with an empty spliced registry resolves mysql through discovery" in {
        Abort.run[SqlException](SqlBackendDiscoveryWrapper.resolve("mysql" + "://u:p@localhost:3306/db")).map {
            case Result.Success((url, factory)) =>
                assert(url.address.scheme == "mysql", s"parsed scheme was ${url.address.scheme}")
                assert(factory.getClass.getName == mysqlFactory, s"resolved ${factory.getClass.getName}")
            case other =>
                fail(s"expected the wrapper to reach mysql through runtime discovery, got $other")
        }
    }

    "a cross-module wrapper with an empty spliced registry resolves postgres through discovery" in {
        Abort.run[SqlException](SqlBackendDiscoveryWrapper.resolve("postgres" + "://u:p@localhost:5432/db")).map {
            case Result.Success((url, factory)) =>
                assert(url.address.scheme == "postgres", s"parsed scheme was ${url.address.scheme}")
                assert(factory.getClass.getName == postgresFactory, s"resolved ${factory.getClass.getName}")
            case other =>
                fail(s"expected the wrapper to reach postgres through runtime discovery, got $other")
        }
    }

    "the open path still fails typed for a scheme neither tier claims, naming both tiers' schemes" in {
        val computed = "sqlite" + "://u:p@localhost:5432/db"
        Abort.run[SqlException](SqlClient.factoryFor(computed, new Backend.Registry(Chunk.empty))).map {
            case Result.Failure(e: SqlConnectionUnsupportedSchemeException) =>
                assert(e.scheme == "sqlite", s"the failure named ${e.scheme}")
                assert(
                    e.available.toSeq.filterNot(_ == "stub").sorted == Seq("mysql", "postgres", "postgresql"),
                    s"the failure must name what discovery can open, got ${e.available}"
                )
            case other =>
                fail(s"expected SqlConnectionUnsupportedSchemeException for an unclaimed scheme, got $other")
        }
    }

end SqlBackendDiscoveryTest
