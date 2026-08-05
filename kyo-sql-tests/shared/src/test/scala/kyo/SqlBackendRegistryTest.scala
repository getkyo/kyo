package kyo
import kyo.db.Backend
import kyo.db.Idiom
import kyo.internal.mysql.MysqlBackendFactory
import kyo.internal.postgres.PostgresBackendFactory

/** Tests for [[kyo.db.Backend.Registry]] and the derivation that builds it from the compile classpath.
  *
  * Both backends ship in this module, so a registry derived here sees both. What the scenarios pin is that the derivation reads the services
  * file rather than a hard-coded list, that scheme lookup covers aliases, and that each discovered factory carries the dialect its backend
  * renders in.
  */
class SqlBackendRegistryTest extends Test:

    private val registry = Backend.Registry.current

    "deriveFindsEveryFactoryOnTheCompileClasspath" in {
        val classes = registry.factories.map(_.getClass.getName).toSeq.sorted
        assert(
            classes == Seq(
                "kyo.internal.mysql.MysqlBackendFactory",
                "kyo.internal.postgres.PostgresBackendFactory"
            ),
            s"derived factories were $classes"
        )
    }

    "schemesListsCanonicalNamesAndAliases" in {
        // The register-only stub shares this test program and, once any leaf registers it, joins the runtime-discovery
        // half of `schemes`, so it is filtered out here; this suite is about the shipping backends' schemes.
        assert(registry.schemes.toSeq.filterNot(_ == "stub").sorted == Seq("mysql", "postgres", "postgresql"))
    }

    "forSchemeResolvesACanonicalScheme" in {
        registry.forScheme("postgres") match
            case Present(f) => assert(f.dialect.id == Idiom.Id("postgres"))
            case Absent     => fail("postgres is not resolvable in a registry that lists it")
        registry.forScheme("mysql") match
            case Present(f) => assert(f.dialect.id == Idiom.Id("mysql"))
            case Absent     => fail("mysql is not resolvable in a registry that lists it")
    }

    "forSchemeResolvesAnAlias" in {
        registry.forScheme("postgresql") match
            case Present(f) => assert(f.scheme == "postgres")
            case Absent     => fail("postgresql is an alias of postgres and must resolve to it")
    }

    "forSchemeIsAbsentForAnUnclaimedScheme" in {
        assert(registry.forScheme("sqlite") == Absent)
        assert(registry.forScheme("") == Absent)
    }

    // The derivation emits `new <factory>` per entry rather than reusing a shared instance, so a factory's
    // zero-argument constructor stays load-bearing and must remain public and free of side effects.
    "eachFactoryDeclaresTheSchemeAndDialectItsBackendOwns" in {
        val pg = new PostgresBackendFactory
        assert(pg.scheme == "postgres")
        assert(pg.aliases == Set("postgresql"))
        assert(pg.dialect.id == Idiom.Id("postgres"))
        val my = new MysqlBackendFactory
        assert(my.scheme == "mysql")
        assert(my.aliases.isEmpty)
        assert(my.dialect.id == Idiom.Id("mysql"))
    }

end SqlBackendRegistryTest
