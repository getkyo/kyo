package kyo.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** The single `@JSImport("three")` facade serving both the JS (CommonJS) and Wasm (ESModule) Scala.js
  * backends (the NodeBuiltins shape).
  *
  * `@JSImport` compiles to `require(...)` under CommonJS and to `import` under ESModule, so one source
  * works on both backends; a `js.Dynamic.global.require` would not (`require` is not a global under the
  * Node ES module the Wasm backend mandates). Members are typed `js.Object` and the reconciler drops to
  * `js.Dynamic` at the bridge sites (each `// Unsafe:`-commented in `ThreeFacadeOps`); `js.Dynamic`
  * never leaks into the public surface (only the sanctioned `Three.custom` seam exposes it).
  *
  * The classes this facade covers: scene-graph (`Scene`, `Group`, `Object3D`,
  * `Mesh`), geometries (`BoxGeometry`, `SphereGeometry`, `PlaneGeometry`, `CylinderGeometry`,
  * `ConeGeometry`, `TorusGeometry`), materials (`MeshBasicMaterial`, `MeshStandardMaterial`,
  * `LineBasicMaterial`, `PointsMaterial`), lights (`AmbientLight`, `DirectionalLight`, `PointLight`,
  * `SpotLight`, `HemisphereLight`), cameras (`PerspectiveCamera`, `OrthographicCamera`), the renderer
  * (`WebGLRenderer`, `WebGLRenderTarget`), `Raycaster`, `Vector2`/`Vector3`, `Color`, and
  * `Texture`/`TextureLoader`. `AnimationMixer` is future roadmap and is not facaded here.
  */
@js.native
@JSImport("three", JSImport.Namespace)
private[kyo] object ThreeFacade extends js.Object:
    // Constructors the reconciler invokes via `js.Dynamic` at the bridge (// Unsafe: sites live in
    // ThreeFacadeOps); typed as js.Object here, mirroring NodeBuiltins.
    val Scene: js.Dynamic                = js.native
    val Group: js.Dynamic                = js.native
    val Object3D: js.Dynamic             = js.native
    val Mesh: js.Dynamic                 = js.native
    val BoxGeometry: js.Dynamic          = js.native
    val SphereGeometry: js.Dynamic       = js.native
    val PlaneGeometry: js.Dynamic        = js.native
    val CylinderGeometry: js.Dynamic     = js.native
    val ConeGeometry: js.Dynamic         = js.native
    val TorusGeometry: js.Dynamic        = js.native
    val MeshBasicMaterial: js.Dynamic    = js.native
    val MeshStandardMaterial: js.Dynamic = js.native
    val LineBasicMaterial: js.Dynamic    = js.native
    val PointsMaterial: js.Dynamic       = js.native
    val AmbientLight: js.Dynamic         = js.native
    val DirectionalLight: js.Dynamic     = js.native
    val PointLight: js.Dynamic           = js.native
    val SpotLight: js.Dynamic            = js.native
    val HemisphereLight: js.Dynamic      = js.native
    val PerspectiveCamera: js.Dynamic    = js.native
    val OrthographicCamera: js.Dynamic   = js.native
    val WebGLRenderer: js.Dynamic        = js.native
    val WebGLRenderTarget: js.Dynamic    = js.native
    val Raycaster: js.Dynamic            = js.native
    val Vector2: js.Dynamic              = js.native
    val Vector3: js.Dynamic              = js.native
    val Color: js.Dynamic                = js.native
    val Texture: js.Dynamic              = js.native
    val TextureLoader: js.Dynamic        = js.native
end ThreeFacade

/** The `GLTFLoader` facade: a three.js subpath module, imported the same cross-backend way as the bare
  * package.
  */
@js.native
@JSImport("three/examples/jsm/loaders/GLTFLoader.js", JSImport.Namespace)
private[kyo] object GltfFacade extends js.Object:
    val GLTFLoader: js.Dynamic = js.native
end GltfFacade
