package kyo.internal

import kyo.*
import kyo.db.Backend

/** A backend-agnostic wrapper around backend resolution, compiled where NO backend is on the classpath.
  *
  * This exists to make one claim observable rather than inferred. `Backend.Registry.current` is derived per CALL SITE at the compile time of
  * the module holding that call site, so a library that wraps resolution behind a non-inline signature freezes ITS OWN registry into its
  * artifact. `kyo-sql` compiles with neither backend module on its classpath, which is the guarantee the module split exists to establish, so
  * the `Backend.Registry.current` below splices `new Backend.Registry(Chunk.empty)` and nothing that happens downstream can add to it.
  *
  * A caller that depends on the backends therefore reaches them only through runtime discovery, which is what `SqlBackendDiscoveryTest`
  * asserts from `kyo-sql-tests`, where both backends are present. That is the J6 plugin-dispatch journey reduced to its resolution step: the
  * wrapper is the plugin compiled against core alone, the caller is the host that has the backend artifacts.
  *
  * The signature takes a plain `String` deliberately. A literal here would be a compile error, correctly, since no backend on this module's
  * classpath claims any scheme; an opaque parameter is what defers the whole question to run time, and it is the shape a real wrapper has.
  * `factoryFor` rather than `init` because the claim under test is which factory answers a scheme, and opening a connection would add a
  * network dependency that has nothing to do with it.
  */
private[kyo] object SqlBackendDiscoveryWrapper:

    /** The registry this module compiled against, which is empty and is meant to be. Exposed so a caller can assert that rather than take it
      * on trust: an assertion about runtime discovery is only meaningful if the compile-time tier is known to be empty here.
      */
    val registry: Backend.Registry = Backend.Registry.current

    /** Resolves `url`'s scheme against this module's own registry, so any factory that answers came from runtime discovery. */
    def resolve(url: String)(using Frame): (SqlConfig.Url, Backend) < Abort[SqlException] =
        SqlClient.factoryFor(url, registry)

end SqlBackendDiscoveryWrapper
