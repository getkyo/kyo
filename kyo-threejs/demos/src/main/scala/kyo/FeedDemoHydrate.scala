package kyo

import scala.scalajs.js.annotation.JSExportTopLevel

/** Client hydrate entries for the server-driven demos (FeedClock, FeedChunk, FeedEmit, Flagship):
  * rebuilds the SAME scene-builder tree client-side (so `data-kyo-path` matches the server's SSR
  * markup by construction) and hydrates it onto the ALREADY-SSR'd DOM via the public `UI.runHydrate`
  * entry -- registering the embedded `Three.embed` canvas's live mount WITHOUT touching
  * `container.innerHTML`. Reactivity rides the page's own inline WS listener (`HtmlRenderer.clientJs`);
  * this only gets the 3D canvas's live mount registered so that listener's dispatch, and a click
  * raycast's `BackendEvent` post, have somewhere to land.
  *
  * Lives in package `kyo` (not `demoharness`, unlike `DemoMounts`'s other entries) because
  * `Frame.internal` is `private[kyo]`, the zero-derivation frame each entry point needs since package
  * `kyo` non-test code cannot auto-derive one. Mirrors `ServerBridgeHydrate`.
  */
object FeedDemoHydrate:

    /** Hydrates [[demo.FeedClockScene]]: a client-spinning cube whose color a server launcher drives. */
    @JSExportTopLevel("hydrateFeedClock")
    def hydrateFeedClock(): Unit =
        given Frame = Frame.internal
        hydrate {
            demo.FeedClockScene.sceneWithMirror.map { case (scene, _) =>
                UI.div(Three.embed(scene, demo.FeedClockScene.camera).id("app"))
            }
        }
    end hydrateFeedClock

    /** Hydrates [[demo.FeedChunkScene]]: a keyed field of cubes whose count/arrangement a server
      * launcher drives.
      */
    @JSExportTopLevel("hydrateFeedChunk")
    def hydrateFeedChunk(): Unit =
        given Frame = Frame.internal
        hydrate {
            demo.FeedChunkScene.sceneWithMirror.map { case (scene, _) =>
                UI.div(Three.embed(scene, demo.FeedChunkScene.camera).id("app"))
            }
        }
    end hydrateFeedChunk

    /** Hydrates [[demo.FeedEmitScene]]: a cube whose click-raycast the server resolves, stepping its
      * color.
      */
    @JSExportTopLevel("hydrateFeedEmit")
    def hydrateFeedEmit(): Unit =
        given Frame = Frame.internal
        hydrate {
            demo.FeedEmitScene.sceneWithMirror.map { case (scene, _) =>
                UI.div(Three.embed(scene, demo.FeedEmitScene.camera).id("app"))
            }
        }
    end hydrateFeedEmit

    /** Hydrates [[demo.FlagshipScene]]: the consolidated showcase (client spin, server-driven color,
      * click-driven scale, orbit controls) on one cube.
      */
    @JSExportTopLevel("hydrateFlagship")
    def hydrateFlagship(): Unit =
        given Frame = Frame.internal
        hydrate {
            demo.FlagshipScene.sceneWithMirrors.map { case (scene, _, _) =>
                UI.div(Three.embed(scene, demo.FlagshipScene.camera).id("app"))
            }
        }
    end hydrateFlagship

    /** Runs a hydrate on a detached fiber whose ambient `Scope` stays open for the page lifetime,
      * mirroring `ServerBridgeHydrate`'s mount boundary, parked forever (a live demo, not a test
      * harness with a close trigger).
      */
    private def hydrate(tree: UI < (Async & Scope))(using Frame): Unit =
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope
        // stays open for the mount's frame loop and observe fibers.
        import AllowUnsafe.embrace.danger
        val held: Unit < Async =
            Scope.run {
                for
                    t <- tree
                    _ <- UI.runHydrate(t)
                    _ <- Async.never
                yield ()
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(held).unit)
    end hydrate

end FeedDemoHydrate
