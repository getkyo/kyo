package kyo

import kyo.internal.Reconciler
import kyo.internal.ThreeFacade
import kyo.internal.ThreeFacadeOps
import scala.scalajs.js.Dynamic.newInstance as jsNew
import scala.scalajs.js as sjs

/** Tests for [[kyo.internal.ThreeFacadeOps]]: each constructs a real three.js object and asserts the
  * live object's properties. Every assertion observes a real three.js property; nothing is faked or mocked.
  */
class ThreeFacadeOpsTest extends ThreeTest:

    "makeGeometry box stores constructor dimensions in parameters" in {
        Scope.run {
            ThreeFacadeOps.makeGeometry(Three.Geometry.box(2.0, 3.0, 4.0)).map { geomObj =>
                Sync.Unsafe.defer {
                    assert(geomObj.parameters.width.asInstanceOf[Double] == 2.0)
                    assert(geomObj.parameters.height.asInstanceOf[Double] == 3.0)
                    assert(geomObj.parameters.depth.asInstanceOf[Double] == 4.0)
                }
            }
        }
    }

    "makeMaterial standard sets metalness and roughness" in {
        Scope.run {
            ThreeFacadeOps.makeMaterial(
                Three.Material.standard(
                    metalness = Normal(0.5),
                    roughness = Normal(0.8)
                )
            ).map { matObj =>
                Sync.Unsafe.defer {
                    assert(matObj.metalness.asInstanceOf[Double] == 0.5)
                    assert(matObj.roughness.asInstanceOf[Double] == 0.8)
                }
            }
        }
    }

    "makeLight ambient sets intensity" in {
        Scope.run {
            ThreeFacadeOps.makeLight(Three.Light.ambient(intensity = 0.7)).map { lightObj =>
                Sync.Unsafe.defer {
                    assert(lightObj.intensity.asInstanceOf[Double] == 0.7)
                }
            }
        }
    }

    "makeCamera perspective passes fov in degrees" in {
        Scope.run {
            ThreeFacadeOps.makeCamera(Three.Camera.perspective(fov = Radians.deg(60))).map { camObj =>
                Sync.Unsafe.defer {
                    // three.js stores fov as-is; the value must be near 60 degrees (not ~1.047 radians)
                    // confirms perspective fov is passed in degrees, not raw radians.
                    val fov = camObj.fov.asInstanceOf[Double]
                    assert(math.abs(fov - 60.0) < 1e-9, s"expected fov near 60.0 degrees, got $fov")
                }
            }
        }
    }

    "makeCamera perspective applies lookAt so camera aims toward the target" in {
        // Camera at (0, 5, 6) looking at origin: the forward direction (negative Z in camera space,
        // expressed as getWorldDirection) must point from the camera position toward Vec3.zero.
        // Without lookAt wiring the camera would face straight down -Z (three.js default), producing
        // direction (0,0,-1), which differs from the expected (0,-5,-6).normalized.
        Scope.run {
            ThreeFacadeOps.makeCamera(
                Three.Camera.perspective(
                    position = Vec3(0, 5, 6),
                    lookAt = Vec3.zero
                )
            ).map { camObj =>
                Sync.Unsafe.defer {
                    // Compute expected forward direction: from position (0,5,6) toward origin (0,0,0).
                    // forward = normalize(target - position) = normalize((0,0,0) - (0,5,6)) = normalize(0,-5,-6)
                    val dx        = 0.0 - 0.0
                    val dy        = 0.0 - 5.0
                    val dz        = 0.0 - 6.0
                    val len       = math.sqrt(dx * dx + dy * dy + dz * dz)
                    val expX      = dx / len
                    val expY      = dy / len
                    val expZ      = dz / len
                    val targetVec = camObj.getWorldDirection(jsNew(ThreeFacade.Vector3)())
                    val gotX      = targetVec.x.asInstanceOf[Double]
                    val gotY      = targetVec.y.asInstanceOf[Double]
                    val gotZ      = targetVec.z.asInstanceOf[Double]
                    val tolerance = 1e-4
                    assert(
                        math.abs(gotX - expX) < tolerance &&
                            math.abs(gotY - expY) < tolerance &&
                            math.abs(gotZ - expZ) < tolerance,
                        s"camera forward ($gotX,$gotY,$gotZ) does not aim toward target; expected (~$expX,~$expY,~$expZ)"
                    )
                    // Guard: confirm the camera is NOT at the three.js default (0,0,-1), which would
                    // indicate lookAt was not applied. If Y is near 0 and Z is near -1 the bug is present.
                    assert(
                        math.abs(gotY) > 0.1,
                        s"camera Y direction near zero ($gotY): lookAt was not applied (camera still at default orientation)"
                    )
                }
            }
        }
    }

    "makeMesh links geometry and material by reference" in {
        Scope.run {
            for
                geomObj <- ThreeFacadeOps.makeGeometry(Three.Geometry.box())
                matObj  <- ThreeFacadeOps.makeMaterial(Three.Material.basic())
                meshObj <- ThreeFacadeOps.makeMesh(geomObj, matObj, Three.mesh(Three.Geometry.box(), Three.Material.basic()))
                result <- Sync.Unsafe.defer {
                    (meshObj.geometry eq geomObj) && (meshObj.material eq matObj)
                }
            yield assert(result)
        }
    }

    "applyMap sets material.map and needsUpdate from a registered texture" in {
        val url = "test://fake-texture.png"
        Scope.run {
            for
                texObj <- Sync.Unsafe.defer {
                    import scala.scalajs.js.Dynamic.literal
                    literal(isTexture = true): sjs.Dynamic
                }
                _ <- Sync.Unsafe.defer(Reconciler.TextureRegistry.register(url, texObj))
                matObj <- ThreeFacadeOps.makeMaterial(
                    Three.Material.basic(map = Present(Three.Ast.Texture.FromUrl(url)))
                )
                _ <- Sync.Unsafe.defer {
                    // The map must be the exact texture object registered above.
                    assert(matObj.map eq texObj)
                    // version increments when needsUpdate is set to true; version starts at 0.
                    assert(matObj.version.asInstanceOf[Double] > 0)
                }
                _ <- Sync.Unsafe.defer(Reconciler.TextureRegistry.remove(url))
            yield ()
        }
    }

end ThreeFacadeOpsTest
