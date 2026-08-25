package kyo.db

import kyo.<
import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.SqlClient
import kyo.SqlConfig
import kyo.SqlException
import kyo.internal.BackendPlatformSpecific

/** One database engine's plug point.
  *
  * A backend is an artifact carrying one of these: the URL scheme it answers to, the [[kyo.db.Idiom]] it renders in, and the entry that
  * opens a client for a URL. Which engines an application can reach is decided by which artifacts it depends on, and no type above this one
  * names an engine.
  *
  * Resolution has two tiers. A call site that opens a client compiles against the backends on its compile classpath, spliced into a
  * [[Backend.Registry]] at that site; a URL the compiler could not read falls through at run time to whatever the running program can
  * discover. A compile-time backend wins every scheme it claims, so what an application was compiled against cannot be displaced by what
  * happens to be present when it runs. Rendering a query while compiling reaches each engine's dialect through the backend carrying it, so
  * a backend on the compile classpath is also what makes its engine available to a statically rendered query.
  *
  * An implementation ships a public zero-argument constructor with no side effects. It is constructed at every literal-URL call site while
  * compiling as well as when the program runs, so construction must be free and must touch neither the network nor the filesystem. The
  * class's fully-qualified name is part of the binary contract, because what a call site splices is a constructor call to it.
  *
  * #### Adding a backend
  *
  * A backend lives in the vendor's own package, in any artifact:
  *
  *   - **Every platform**: the backend class, and one `META-INF/services/kyo.db.Backend` entry naming it in shared resources. That is the
  *     only services contract, the dialect being reached through [[dialect]] rather than registered on its own.
  *   - **JVM**: nothing further. `ServiceLoader` covers runtime discovery.
  *   - **JS and Wasm**: one `@JSExportTopLevel` object whose initializer calls `Backend.register`, because linker dead-code elimination
  *     drops an initializer nothing references.
  *   - **Native**: nothing further in the artifact. The application enlists the class in
  *     `nativeConfig.withServiceProviders(Map("kyo.db.Backend" -> Seq("com.vendor.VendorBackend")))`, since Scala Native resolves service
  *     providers at link time.
  *
  * Omitting a platform's runtime registration leaves the backend reachable through a literal URL and invisible to a computed one, with no
  * error at either point.
  *
  * @see
  *   [[kyo.db.Idiom]] The translator a backend names as its dialect
  * @see
  *   [[kyo.db.Connection]] The session type a backend implements
  * @see
  *   [[kyo.db.Runtime.init]] The assembly [[open]] calls
  * @see
  *   [[Backend.Registry]] What a scheme is resolved against
  */
abstract class Backend:

    /** The URL scheme this backend answers to, such as `"postgres"`. */
    def scheme: String

    /** Further schemes that select this backend, such as `"postgresql"` for Postgres. Empty when it has only its canonical [[scheme]]. */
    def aliases: Set[String]

    /** The [[kyo.db.Idiom]] this backend renders in, and the one a statically rendered query reaches for this engine. */
    def dialect: Idiom

    /** Whether this backend answers a URL whose scheme is `candidate`: true when `candidate` is [[scheme]] or one of [[aliases]].
      *
      * Final. Every site that resolves a scheme asks this one question through this one member, the compile-time refusal and the registry
      * alike, and an overridable predicate could answer differently from the two members it reads.
      */
    final def claims(candidate: String): Boolean =
        candidate == scheme || aliases.contains(candidate)

    /** Opens a warmed client for `url` under `config`.
      *
      * The caller owns the close, and the scoped form core derives wraps this call in the enclosing [[kyo.Scope]]. An implementation
      * validates whatever its own settings require, calls [[kyo.db.Runtime.init]] to assemble, and wraps the carrier it gets back in its
      * client class.
      *
      * A backend does not resolve the URL's own declarations: `init` merges them under `config`, and the merged value is the settings the
      * returned client was opened under.
      */
    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException])

end Backend

/** Companion of [[Backend]]: the [[Registry]] a scheme is resolved against, and the entry that derives one for a call site. */
object Backend extends BackendPlatformSpecific:

    /** The backends one call site can open: the compile-time ones fixed when that call site was compiled, plus whatever the running program
      * can still discover.
      *
      * The two tiers are ordered rather than merged. A registry's own backends are consulted first and one of them wins every scheme it
      * claims; runtime discovery answers only a scheme none of them claims. The compile classpath is the reviewable input, the one the
      * compile-time refusal was computed against and the one whose constructor calls the registry already carries, so a provider that
      * happens to be present when the program runs cannot silently retarget a scheme the application was compiled for, and resolution stays
      * deterministic for a given build.
      *
      * Construction is public, and it is what a test or a composed resolution layer uses; [[Registry.current]] is what derives one from a
      * classpath.
      *
      * @param factories
      *   the compile-time tier, in the order the call site's expansion produced them
      */
    final class Registry(private[kyo] val factories: Chunk[Backend]):

        /** Every scheme a client can be opened for: this registry's own backends in their own order, then any further scheme reachable only
          * through runtime discovery, without duplicates.
          *
          * What [[kyo.SqlConnectionUnsupportedSchemeException]] reports as available, so it answers what the caller can actually open
          * rather than only what the compiler saw.
          *
          * Note: reading this triggers runtime discovery, which is why resolving a single scheme does not go through here.
          */
        def schemes: Chunk[String] =
            (Registry.schemesOf(factories) ++ Registry.schemesOf(kyo.internal.SqlBackendDiscovery.factories)).distinct

        /** The backend claiming `scheme`, by canonical name or alias, or [[kyo.Absent]] when neither tier has one.
          *
          * Discovery is consulted only when this registry's own backends come up empty, so a program whose schemes are all claimed at
          * compile time never pays for it.
          */
        private[kyo] def forScheme(scheme: String): Maybe[Backend] =
            Registry.claimant(factories, scheme).orElse(Registry.claimant(kyo.internal.SqlBackendDiscovery.factories, scheme))

    end Registry

    object Registry:

        /** A registry over the backends on the caller's compile classpath, resolved while compiling and carrying no reflection into the
          * running program.
          *
          * The entry for deferred resolution: a library that leaves the choice of engine to its caller takes a registry as an ordinary
          * parameter, and the application writes this at its own call site, where the expansion runs against the application's classpath
          * rather than the library's.
          */
        inline def current: Registry =
            ${ kyo.internal.SqlBackendRegistryMacro.deriveImpl }

        /** Every scheme `factories` answers to: each backend's canonical scheme, then its aliases sorted, so the order is stable. */
        private def schemesOf(factories: Chunk[Backend]): Chunk[String] =
            factories.flatMap(f => Chunk(f.scheme) ++ Chunk.from(f.aliases.toSeq.sorted))

        /** The first backend in `factories` claiming `scheme`, or [[kyo.Absent]] when none of them does. */
        private def claimant(factories: Chunk[Backend], scheme: String): Maybe[Backend] =
            Maybe.fromOption(factories.find(_.claims(scheme)))

    end Registry

end Backend
