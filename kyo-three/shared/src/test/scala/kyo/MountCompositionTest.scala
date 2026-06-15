package kyo

import kyo.*

/** Guards that kyo-three's `Three.runMount` and kyo-ui's `UI.runMount` coexist. `Three.runMount`
  * is a public member of `object Three` (reachable via `import kyo.*`) and kyo-ui's `UI.runMount`
  * is a top-level extension in `package kyo` (also reachable via `import kyo.*`). Distinct
  * receiver types (`Three.type` vs `UI.type`) keep them unambiguous in the same scope.
  *
  * The first three fixtures pin the kyo-three side (`Three.runMount` reachable via `import kyo.*`,
  * with and without the frames argument); the fourth pins the cross-module composition:
  * `UI.runMount` and `Three.runMount` both resolve and compose in one file with a single import.
  */
class MountCompositionTest extends ThreeTest:

    "Three.runMount resolves via import kyo.* (no frames arg)" in {
        val scene                                             = Three.scene()
        val camera                                            = Three.Camera.perspective()
        val _: Unit < (Async & Scope & Abort[ThreeException]) = Three.runMount(scene, camera, "#canvas")
        succeed
    }

    "Three.runMount resolves via import kyo.* (with ThreeFrames.Raf)" in {
        val scene  = Three.scene()
        val camera = Three.Camera.perspective()
        val _: Unit < (Async & Scope & Abort[ThreeException]) =
            Three.runMount(scene, camera, "#canvas", ThreeFrames.Raf)
        succeed
    }

    "Three.runMount is a public member of object Three" in {
        // The type-ascription compiles because runMount is a regular member of object Three,
        // brought into scope by import kyo.*. Distinct receiver types (Three vs UI) keep
        // Three.runMount and UI.runMount unambiguous in the same file.
        val scene                                             = Three.scene()
        val camera                                            = Three.Camera.perspective()
        val _: Unit < (Async & Scope & Abort[ThreeException]) = Three.runMount(scene, camera, "#canvas")
        succeed
    }

    "UI.runMount and Three.runMount compose in one file with import kyo.*" in {
        // The load-bearing guard: UI.runMount (top-level UI.type extension) and
        // Three.runMount (member of object Three) both resolve with a single import kyo.*
        // and compose in one for-comprehension; distinct receiver types keep them unambiguous.
        val composed: Unit < (Async & Scope & Abort[ThreeException]) =
            for
                _ <- UI.runMount(UI.div(), "#hud")
                _ <- Three.runMount(Three.scene(), Three.Camera.perspective(), "#app")
            yield ()
        val _ = composed
        succeed
    }

end MountCompositionTest
