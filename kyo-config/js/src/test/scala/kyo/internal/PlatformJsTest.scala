package kyo.internal

import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.LinkingInfo
import scala.scalajs.js

class PlatformJsTest extends AnyFreeSpec {

    import Platform.Host

    /** Runs `f` with `process` deleted from the global object, the state of a browser or a WasmGC host without a Node shim. Restored in a
      * `finally` because the test runner talks over `process.stdout`.
      */
    private def withoutProcessGlobal[A](f: => A): A = {
        val global = js.Dynamic.global.globalThis
        val saved  = js.Dynamic.global.process
        js.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
    }

    private def node: js.Dynamic = js.Dynamic.literal(process = js.Dynamic.literal(versions = js.Dynamic.literal(node = "24.0.0")))

    "detectHost" - {
        "classifies Node by process.versions.node" in {
            assert(PlatformJs.detectHost(node) eq Host.Node)
        }

        "classifies Bun and Deno ahead of Node, since both also define process" in {
            val bun = node
            bun.updateDynamic("Bun")(js.Dynamic.literal())
            assert(PlatformJs.detectHost(bun) eq Host.Bun)
            val deno = node
            deno.updateDynamic("Deno")(js.Dynamic.literal())
            assert(PlatformJs.detectHost(deno) eq Host.Deno)
        }

        "classifies a browser page by window and document" in {
            val page = js.Dynamic.literal(window = js.Dynamic.literal(), document = js.Dynamic.literal())
            assert(PlatformJs.detectHost(page) eq Host.BrowserMain)
        }

        "does not take navigator as a browser signal, since Node defines it" in {
            val withNavigator = js.Dynamic.literal(navigator = js.Dynamic.literal(language = "en-US"))
            assert(PlatformJs.detectHost(withNavigator) eq Host.OtherJs)
        }

        "classifies a worker by its WorkerGlobalScope" in {
            val scope  = js.eval("(function WorkerGlobalScope() {})").asInstanceOf[js.Dynamic]
            val worker = js.Dynamic.newInstance(scope)()
            worker.updateDynamic("WorkerGlobalScope")(scope)
            assert(PlatformJs.detectHost(worker) eq Host.BrowserWorker)
        }

        "classifies anything else as another JS host" in {
            assert(PlatformJs.detectHost(js.Dynamic.literal()) eq Host.OtherJs)
        }
    }

    "on the Node test host" - {
        "detects Node" in {
            assert(Platform.host eq Host.Node)
            assert(Platform.isNodeLike && !Platform.isBrowser)
        }

        "resolves isWasm from the link, not from the compiled artifact" in {
            assert(Platform.isWasm == LinkingInfo.isWebAssembly)
        }

        "resolves canSplitModules from the link: never under WasmGC or NoModule" in {
            val expected = !LinkingInfo.isWebAssembly && LinkingInfo.moduleKind != LinkingInfo.ModuleKind.NoModule
            assert(Platform.canSplitModules == expected)
            assert(!(Platform.canSplitModules && Platform.isWasm))
        }

        "classifies process.platform and process.arch" in {
            assert(Platform.os eq Platform.Os.fromNodePlatform(js.Dynamic.global.process.platform.asInstanceOf[String]))
            assert(Platform.arch eq Platform.Arch.fromToken(js.Dynamic.global.process.arch.asInstanceOf[String]))
        }

        "reaches a Node built-in synchronously without a static import" in {
            val platformType = PlatformJs.nodeBuiltin("node:os").fold("undefined")(os => js.typeOf(os.platform))
            assert(platformType == "function")
            val missing = PlatformJs.nodeBuiltin("node:kyo-no-such-module").isEmpty
            assert(missing)
        }
    }

    "without a process global" - {
        "stops treating the host as Node-like" in {
            withoutProcessGlobal {
                assert(!Platform.isNodeLike)
            }
        }

        "exits without throwing" in {
            val outcome = withoutProcessGlobal {
                try {
                    Platform.exit(0)
                    "ok"
                } catch {
                    case e: Throwable => e.toString
                }
            }
            assert(outcome == "ok")
        }

        "finds no Node built-ins" in {
            val (noBuiltin, noProcess) = withoutProcessGlobal {
                (PlatformJs.nodeBuiltin("node:os").isEmpty, PlatformJs.jsGlobal("process").isEmpty)
            }
            assert(noBuiltin)
            assert(noProcess)
        }
    }
}
