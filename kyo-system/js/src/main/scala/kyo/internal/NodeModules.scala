package kyo.internal

import scala.scalajs.js

/** Node's built-in modules for kyo-system's `Path` and `Process` backends.
  *
  * Each is reached through `process.getBuiltinModule` at the call ([[PlatformJs.nodeBuiltin]]), never a static `@JSImport`. A static import
  * of a `node:` module is hoisted and resolved when the bundle loads, so a browser page that merely links `Path` or `Command` would fail to
  * load at all. A lookup at the call finds nothing on such a host, and the operation fails with the `UnsupportedOperationException` of
  * [[unsupported]], which names the module and the host.
  *
  * Nothing is cached: the lookup is a property read and a map lookup in Node, small beside the system call each operation makes, and an
  * uncached lookup answers for the host as it is at the call.
  *
  * The facade traits carry an `Api` suffix so no name here collides with another module's `kyo.internal` class; kyo-flow has a shared
  * `kyo.internal.NodePath`.
  */
private[kyo] object NodeModules:

    /** `node:fs`. */
    def fs: NodeFsApi = module("node:fs").asInstanceOf[NodeFsApi]

    /** `node:path`. */
    def path: NodePathApi = module("node:path").asInstanceOf[NodePathApi]

    /** `node:os`. */
    def os: NodeOsApi = module("node:os").asInstanceOf[NodeOsApi]

    /** `node:child_process`. */
    def childProcess: NodeChildProcessApi = module("node:child_process").asInstanceOf[NodeChildProcessApi]

    /** Whether the host provides `id` through `process.getBuiltinModule`. */
    def isAvailable(id: String): Boolean = PlatformJs.nodeBuiltin(id).isDefined

    /** The failure of an operation that needs `id` on a host that does not provide it. */
    def unsupported(id: String): UnsupportedOperationException =
        new UnsupportedOperationException(
            s"kyo-system needs Node's $id module for this operation (Node 20.16 or 22.3, Bun 1.2.6, Deno 2.1, or later); this host is ${Platform.host}"
        )

    private def module(id: String): js.Dynamic =
        PlatformJs.nodeBuiltin(id).getOrElse(throw unsupported(id))

end NodeModules
