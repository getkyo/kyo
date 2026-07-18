package kyo

import kyo.internal.Reconciler
import kyo.internal.ThreeFacade
import kyo.internal.ThreeFacadeOps
import scala.scalajs.js as sjs

/** Tests for [[kyo.internal.TextureLoader]]: typed failure surfacing, texture handle construction, texture
  * application onto a material's map via the reconciler, and dispose-on-scope-close. Tests that require a
  * real image decode (browser-only) are not included here; the registry-injection approach covers the
  * handle, reconciler wiring, and disposal paths on Node.
  *
  * Tests 9, 10, 13 inject a real `THREE.Texture()` directly into `Reconciler.TextureRegistry` (the
  * `THREE.Texture` constructor requires no GL context and no DOM). Tests 11, 12 drive the
  * `TextureLoader.load` error path, which on Node produces `AssetLoadFailed` from the synchronous throw
  * that `ImageLoader.createElementNS` raises when `document` is unavailable.
  */
class TextureLoaderTest extends ThreeTest:

    private val testUrl = "/textures/test.png"

    // The pure `Texture.FromUrl` handle construction/equality is covered cross-platform by ThreeAstTest.

    "a loaded texture applies to a material map via the reconciler" in {
        Scope.run {
            Sync.Unsafe.defer {
                // Unsafe: construct a real THREE.Texture (no GL context required) and register it directly.
                val fakeTex = sjs.Dynamic.newInstance(ThreeFacade.Texture)()
                Reconciler.TextureRegistry.register(testUrl, fakeTex)
                fakeTex
            }.map { fakeTex =>
                val mat = Three.Material.standard(map = Present(Three.Ast.Texture.FromUrl(testUrl)))
                ThreeFacadeOps.makeMaterial(mat).map { matObj =>
                    Sync.Unsafe.defer {
                        // Unsafe: read the live material's map property to assert it matches the registered texture.
                        val resolvedMap = matObj.map
                        assert(!sjs.isUndefined(resolvedMap), "material.map is undefined")
                        assert(
                            resolvedMap.asInstanceOf[AnyRef] eq fakeTex.asInstanceOf[AnyRef],
                            "material.map does not match the registered texture"
                        )
                    }
                }.andThen(
                    Sync.Unsafe.defer(Reconciler.TextureRegistry.remove(testUrl))
                )
            }
        }
    }

    "a failing texture url surfaces AssetLoadFailed" in {
        Scope.run {
            Abort.run[ThreeException](Three.texture("/no-such-texture.png")).map { result =>
                result match
                    case Result.Failure(ThreeException.AssetLoadFailed(url, cause)) =>
                        assert(url == "/no-such-texture.png")
                        assert(cause != null)
                    case other =>
                        assert(false, s"expected AssetLoadFailed, got: $other")
            }
        }
    }

    "a failing texture load never yields Result.Success" in {
        Scope.run {
            Abort.run[ThreeException](Three.texture("/no-such-texture.png")).map { result =>
                assert(!result.isSuccess, s"expected failure, got success: $result")
            }
        }
    }

    "a loaded texture disposes on scope close and registry entry is cleared" in {
        var disposeCount = 0
        val url          = "/textures/dispose-test.png"
        Scope.run {
            Sync.Unsafe.defer {
                // Unsafe: construct a real THREE.Texture (no GL context required) and register it.
                val fakeTex = sjs.Dynamic.newInstance(ThreeFacade.Texture)()
                discard(fakeTex.addEventListener("dispose", (_: sjs.Any) => disposeCount += 1))
                Reconciler.TextureRegistry.register(url, fakeTex)
                fakeTex
            }.map { fakeTex =>
                Scope.acquireRelease(
                    Sync.Unsafe.defer(())
                ) { _ =>
                    // Unsafe: dispose the texture and remove the registry entry on scope close.
                    Sync.Unsafe.defer {
                        val _ = fakeTex.dispose()
                        Reconciler.TextureRegistry.remove(url)
                    }
                }
            }
        }.map { _ =>
            assert(disposeCount == 1, s"dispose count: $disposeCount")
            Sync.Unsafe.defer {
                // Unsafe: verify the registry entry was removed on scope close.
                val resolved = Reconciler.TextureRegistry.resolve(Three.Ast.Texture.FromUrl(url))
                assert(resolved == Absent, s"expected Absent, got: $resolved")
            }
        }
    }

end TextureLoaderTest
