package kyo

import kyo.internal.PlatformJs
import scala.scalajs.js

/** Node built-in facades for kyo-pod's container-runtime test helper.
  *
  * Declared (typed) members rather than `js.Dynamic`, and resolved at the call through `process.getBuiltinModule` rather than a static
  * `@JSImport`: a static import is hoisted and resolved when the module graph loads, so a page that merely links these tests would fail to
  * load before any test ran. `require` is not the alternative, since it is absent under the ESModule the Wasm backend mandates. Names are
  * Pod-prefixed so they do not clash with the `kyo.*` namespace.
  */
@js.native
private[kyo] trait PodNodeChildProcess extends js.Object:
    def execSync(command: String): js.Dynamic                  = js.native
    def execSync(command: String, options: js.Any): js.Dynamic = js.native

    /** Spawns `command` directly, with no shell between. The result carries `error` when the binary could not be spawned at all and
      * `status` when it ran, which is what tells "not installed" from "installed and failing".
      */
    def spawnSync(command: String, args: js.Array[String], options: js.Any): js.Dynamic = js.native
end PodNodeChildProcess

private[kyo] object PodNodeChildProcess:

    /** `node:child_process` on a host that provides it, and [[Absent]] on one that does not, such as a browser page. */
    def module: Maybe[PodNodeChildProcess] =
        PlatformJs.nodeBuiltin("node:child_process").fold(Absent: Maybe[PodNodeChildProcess]) { childProcess =>
            Present(childProcess.asInstanceOf[PodNodeChildProcess])
        }
end PodNodeChildProcess

@js.native
private[kyo] trait PodNodeOs extends js.Object:
    def homedir(): String = js.native
end PodNodeOs

private[kyo] object PodNodeOs:

    /** `node:os` on a host that provides it, and [[Absent]] on one that does not, such as a browser page. */
    def module: Maybe[PodNodeOs] =
        PlatformJs.nodeBuiltin("node:os").fold(Absent: Maybe[PodNodeOs])(os => Present(os.asInstanceOf[PodNodeOs]))
end PodNodeOs
