package kyo

import kyo.internal.GltfLoader
import kyo.internal.ThreeFacadeOps
import scala.scalajs.js as sjs

/** Tests for [[kyo.internal.GltfLoader]]: typed failure surfacing, real glTF parse via GLTFLoader, named-node
  * index, animation clip names, parallel composition, and dispose-on-scope-close. All tests run on the
  * Node environment using GLTFLoader.parse with an in-memory fixture (no network, no DOM required).
  *
  * Tests 1-3 (error path) use invalid JSON to provoke a synchronous parse failure: GLTFLoader.parse
  * attempts JSON.parse internally and throws, which the try/catch in GltfLoader converts to AssetLoadFailed.
  * This approach avoids Node-incompatible paths (fetch, ProgressEvent) that crash the Node process.
  *
  * The fixture for tests 4-8 has a named node ("Cube") and an animation clip ("CubeAction") but
  * deliberately omits meshes and buffers: the three.js FileLoader path (which emits ProgressEvent, undefined
  * on Node) is only triggered when a buffer URI is present. The geometry-free fixture parses completely
  * in-memory on Node without ProgressEvent.
  *
  * Test 8 (dispose) acquires a real THREE.BoxGeometry under the same Scope as the glTF load and confirms
  * the geometry dispose event fires on scope close, verifying the scope-managed GL resource disposal contract.
  */
class GltfLoaderTest extends ThreeTest:

    // A minimal glTF 2.0 with a named node ("Cube") and one animation ("CubeAction").
    // No mesh, no buffer, no bufferView: avoids the three.js FileLoader path that emits
    // ProgressEvent (undefined on Node). Parses fully in-memory via GLTFLoader.parse.
    private val fixture =
        """{
          |  "asset": { "version": "2.0" },
          |  "scene": 0,
          |  "scenes": [{ "name": "Scene", "nodes": [0] }],
          |  "nodes": [{ "name": "Cube" }],
          |  "animations": [{ "name": "CubeAction", "channels": [], "samplers": [] }]
          |}""".stripMargin

    // Invalid JSON that provokes a synchronous throw from GLTFLoader.parse (JSON.parse fails),
    // which GltfLoader's try/catch converts to AssetLoadFailed. No network I/O or ProgressEvent involved.
    private val invalidJson = "{ not valid json !!!"

    "a failing parse surfaces AssetLoadFailed" in {
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(invalidJson)).map { result =>
                result match
                    case Result.Failure(ThreeException.AssetLoadFailed(_, cause)) =>
                        assert(cause != null)
                    case other =>
                        assert(false, s"expected AssetLoadFailed, got: $other")
            }
        }
    }

    "a failing parse never yields Result.Success" in {
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(invalidJson)).map { result =>
                assert(!result.isSuccess, s"expected failure, got success: $result")
            }
        }
    }

    "a failing parse produces no escaped exception" in {
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(invalidJson)).map { result =>
                assert(result.isFailure)
            }
        }
    }

    "a successful parse returns an Asset.Gltf with a root" in {
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(fixture)).map { result =>
                result match
                    case Result.Success(asset) =>
                        assert(asset.root.isInstanceOf[Three.Ast.Custom[?]], s"expected Custom root, got: ${asset.root}")
                    case other =>
                        assert(false, s"expected success, got: $other")
            }
        }
    }

    "the named-node index is populated" in {
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(fixture)).map { result =>
                result match
                    case Result.Success(asset) =>
                        assert(asset.nodes.contains("Cube"), s"nodes map missing 'Cube': ${asset.nodes.keys}")
                    case other =>
                        assert(false, s"expected success, got: $other")
            }
        }
    }

    "animation clip names are present" in {
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(fixture)).map { result =>
                result match
                    case Result.Success(asset) =>
                        assert(asset.animations == Chunk("CubeAction"), s"animations: ${asset.animations}")
                    case other =>
                        assert(false, s"expected success, got: $other")
            }
        }
    }

    "two loads compose under Async.zip in parallel" in {
        Scope.run {
            Abort.run[ThreeException](
                Async.zip(
                    GltfLoader.loadFromJson(fixture),
                    GltfLoader.loadFromJson(fixture)
                )
            ).map { result =>
                result match
                    case Result.Success((a, b)) =>
                        assert(a.nodes.contains("Cube"))
                        assert(b.animations == Chunk("CubeAction"))
                    case other =>
                        assert(false, s"expected success tuple, got: $other")
            }
        }
    }

    "the loaded root statically accepts a handler without a cast" in {
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(fixture)).map {
                case Result.Success(asset) =>
                    val withHandler = asset.root.onClick(_ => Sync.defer(()))
                    assert(withHandler.props.onClick.isDefined)
                case other =>
                    assert(false, s"expected success, got: $other")
            }
        }
    }

    "a loaded asset scope closes cleanly and scope-managed geometry disposes" in {
        var geomDisposed = 0
        Scope.run {
            Abort.run[ThreeException](GltfLoader.loadFromJson(fixture)).map { result =>
                result match
                    case Result.Success(_) =>
                        // The fixture has no mesh geometry. Verify the scope-managed dispose
                        // mechanism by acquiring a real BoxGeometry under the same Scope and
                        // registering a dispose listener: it must fire exactly once on scope close.
                        ThreeFacadeOps.makeGeometry(Three.Geometry.box()).map { geomObj =>
                            Sync.Unsafe.defer {
                                // Unsafe: register a dispose listener on the real THREE.BufferGeometry.
                                discard(geomObj.addEventListener("dispose", (_: sjs.Any) => geomDisposed += 1))
                            }
                        }
                    case other =>
                        assert(false, s"expected success, got: $other")
            }
        }.map { _ =>
            assert(geomDisposed == 1, s"geometry dispose count: $geomDisposed")
        }
    }

end GltfLoaderTest
