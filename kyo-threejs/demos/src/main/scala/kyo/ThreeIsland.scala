package kyo

import demo.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The Scala.js client island the server-push SSR page loads as an ESModule. On load it scans
  * the document for every `[data-kyo-host]` element, reads that host's inline init payload (the
  * `<script type="application/json" data-kyo-host-init>` island the SSR page emitted), and runs
  * `ThreeMount.islandMount` for it on a detached fiber whose ambient Scope stays open for the
  * page lifetime. The mount opens the per-host channel a server `HtmlOp.HostUpdate` writes into;
  * the inline clientJs routes each HostUpdate to that channel by `data-kyo-path`.
  */
object ThreeIsland:

    @JSExportTopLevel("kyoThreeIsland")
    def kyoThreeIsland(): Unit =
        // Unsafe: the page-to-kyo boundary; runs each host mount on a detached fiber whose Scope
        // holds the channel open for the page lifetime. AllowUnsafe scoped to this entry call.
        // Frame.internal is used here because this is the JS entry point (no user call site to
        // derive a Frame from); the internal frame provides a stable anchor for diagnostics.
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        val _       = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(mountAllHosts()).unit)
    end kyoThreeIsland

    private def mountAllHosts()(using Frame): Unit < Async =
        Scope.run {
            Sync.Unsafe.defer(dom.document.querySelectorAll("[data-kyo-host]")).map { nodes =>
                val hosts = (0 until nodes.length).map(i => nodes(i).asInstanceOf[dom.Element])
                Kyo.foreachDiscard(Chunk.from(hosts)) { el =>
                    ThreeMount.readHostInit(el).map { init =>
                        val path = ThreeMount.hostPath(el)
                        Abort.run[ThreeException](
                            ThreeMount.islandMount(el, path, init)
                        ).map {
                            case Result.Success(_) => (): Unit < Sync
                            case Result.Failure(e) => Log.error(s"island mount failed: ${e.getMessage}")
                            case Result.Panic(e) =>
                                if e.isInstanceOf[Interrupted] then (): Unit < Sync
                                else Log.error("island mount panicked", e)
                        }
                    }
                }.andThen(Async.never)
            }
        }
    end mountAllHosts

end ThreeIsland
