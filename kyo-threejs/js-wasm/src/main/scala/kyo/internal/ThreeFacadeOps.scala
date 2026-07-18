package kyo.internal

import kyo.*
import kyo.Three.Ast
import scala.scalajs.js
import scala.scalajs.js.Dynamic.newInstance as jsNew

/** Typed bridge helpers over [[ThreeFacade]]: each constructs one live three.js object from a pure AST
  * node, acquiring every GL resource under `Scope.acquireRelease(create)(_.dispose())`. Every
  * FFI call site carries an `// Unsafe:` rationale and routes the side-effecting `new`/setter through
  * `Sync.Unsafe.defer`.
  */
private[kyo] object ThreeFacadeOps:

    /** Acquires a disposable three.js GL resource under Scope; `release` calls its `.dispose()`.
      * The `create` context-function is evaluated inside `Sync.Unsafe.defer`, which injects
      * `AllowUnsafe` so callers (e.g. `applyStandard`, `applyMap`) can use unsafe FFI at the call
      * site without requiring `AllowUnsafe` on the public `make*` methods.
      */
    private def acquireGl(create: AllowUnsafe ?=> js.Dynamic)(using Frame): js.Dynamic < (Scope & Sync) =
        Scope.acquireRelease(
            // Unsafe: constructing a live three.js GL resource; deferred so it runs inside the effect.
            Sync.Unsafe.defer(create)
        ) { obj =>
            // Unsafe: releasing the GL resource on scope close; three.js objects expose .dispose().
            Sync.Unsafe.defer { val _ = obj.dispose() }
        }

    /** A plain (non-disposable) Object3D (Group, Scene, Camera, Mesh wrapper) acquired under Scope.
      * The `create` context-function is evaluated inside `Sync.Unsafe.defer` which injects `AllowUnsafe`
      * so `positioned`, `applyMaterialProps`, etc. may be used inside the thunk body.
      */
    private def acquirePlain(create: AllowUnsafe ?=> js.Dynamic)(using Frame): js.Dynamic < (Scope & Sync) =
        // Unsafe: a Group/Object3D holder has no GPU buffer; constructed via Sync.Unsafe.defer.
        Sync.Unsafe.defer(create)

    def makeScene(s: Ast.Scene)(using Frame): js.Dynamic < (Scope & Sync) =
        acquirePlain(jsNew(ThreeFacade.Scene)())

    def makeGroup(g: Ast.Group)(using Frame): js.Dynamic < (Scope & Sync) =
        acquirePlain(jsNew(ThreeFacade.Group)())

    def makeHolder()(using Frame): js.Dynamic < (Scope & Sync) =
        acquirePlain(jsNew(ThreeFacade.Group)())

    def makeGeometry(geom: Ast.Geometry)(using Frame): js.Dynamic < (Scope & Sync) =
        geom match
            case g: Ast.Geometry.Box =>
                acquireGl(jsNew(ThreeFacade.BoxGeometry)(g.width, g.height, g.depth))
            case g: Ast.Geometry.Sphere =>
                acquireGl(jsNew(ThreeFacade.SphereGeometry)(g.radius, g.widthSegments, g.heightSegments))
            case g: Ast.Geometry.Plane =>
                acquireGl(jsNew(ThreeFacade.PlaneGeometry)(g.width, g.height))
            case g: Ast.Geometry.Cylinder =>
                acquireGl(jsNew(ThreeFacade.CylinderGeometry)(g.radiusTop, g.radiusBottom, g.height, g.radialSegments))
            case g: Ast.Geometry.Cone =>
                acquireGl(jsNew(ThreeFacade.ConeGeometry)(g.radius, g.height, g.radialSegments))
            case g: Ast.Geometry.Torus =>
                acquireGl(jsNew(ThreeFacade.TorusGeometry)(g.radius, g.tube, g.radialSegments, g.tubularSegments))
            case g: Ast.Geometry.Custom[?] =>
                // Unsafe: the user's typed build seam producing a raw BufferGeometry (the sanctioned js.Dynamic).
                acquireGl(buildCustom(g.build, g.input))
        end match
    end makeGeometry

    def makeMaterial(mat: Ast.Material)(using Frame): js.Dynamic < (Scope & Sync) =
        mat match
            case m: Ast.Material.Basic =>
                acquireGl(
                    // Unsafe: constructing and configuring a MeshBasicMaterial inside the acquireGl create thunk.
                    applyMap(
                        applyMaterialProps(
                            jsNew(ThreeFacade.MeshBasicMaterial)(),
                            color = boundColor(m.color),
                            opacity = boundNormal(m.opacity)
                        ),
                        m.map
                    )
                )
            case m: Ast.Material.Standard =>
                acquireGl(applyStandard(m))
            case m: Ast.Material.Line =>
                acquireGl(applyMaterialProps(
                    jsNew(ThreeFacade.LineBasicMaterial)(),
                    color = boundColor(m.color),
                    opacity = boundNormal(m.opacity)
                ))
            case m: Ast.Material.Points =>
                acquireGl(applyMaterialProps(
                    jsNew(ThreeFacade.PointsMaterial)(),
                    color = boundColor(m.color),
                    opacity = boundNormal(m.opacity)
                ))
            case m: Ast.Material.Custom[?] =>
                acquireGl(buildCustom(m.build, m.input))
        end match
    end makeMaterial

    def makeLight(light: Ast.Light)(using Frame): js.Dynamic < (Scope & Sync) =
        light match
            case l: Ast.Light.Ambient =>
                acquireGl(jsNew(ThreeFacade.AmbientLight)(boundColor(l.color), boundDouble(l.intensity)))
            case l: Ast.Light.Directional =>
                acquireGl(positioned(jsNew(ThreeFacade.DirectionalLight)(boundColor(l.color), boundDouble(l.intensity)), l.transform))
            case l: Ast.Light.Point =>
                acquireGl(positioned(jsNew(ThreeFacade.PointLight)(boundColor(l.color), boundDouble(l.intensity), l.distance), l.transform))
            case l: Ast.Light.Spot =>
                // SpotLight(color, intensity, distance, angle, penumbra): angle is in radians, no conversion needed.
                acquireGl(positioned(
                    jsNew(ThreeFacade.SpotLight)(boundColor(l.color), boundDouble(l.intensity), 0.0, l.angle.toDouble, l.penumbra.toDouble),
                    l.transform
                ))
            case l: Ast.Light.Hemisphere =>
                acquireGl(jsNew(ThreeFacade.HemisphereLight)(boundColor(l.sky), boundColor(l.ground), boundDouble(l.intensity)))
        end match
    end makeLight

    def makeCamera(camera: Ast.Camera)(using Frame): js.Dynamic < (Scope & Sync) =
        camera match
            case c: Ast.Camera.Perspective =>
                // PerspectiveCamera(fov, aspect, near, far): fov is in DEGREES; toDegrees converts from the
                // Three.Radians opaque type which stores values in radians internally.
                acquirePlain(
                    // Unsafe: position must be set before lookAt so three.js computes orientation from
                    // the correct world position toward the target; both calls share this thunk.
                    aimedCamera(
                        positioned(jsNew(ThreeFacade.PerspectiveCamera)(c.fov.toDegrees, 1.0, c.near, c.far), c.transform),
                        c.lookAt
                    )
                )
            case c: Ast.Camera.Orthographic =>
                acquirePlain(
                    // Unsafe: position before lookAt ordering applies to orthographic cameras too.
                    aimedCamera(
                        positioned(
                            jsNew(ThreeFacade.OrthographicCamera)(-c.viewSize, c.viewSize, c.viewSize, -c.viewSize, c.near, c.far),
                            c.transform
                        ),
                        c.lookAt
                    )
                )
        end match
    end makeCamera

    /** Applies `lookAt` to a camera after its position has been set. `lookAt` must be called after
      * `position.set` so three.js computes orientation from the camera's world position toward the
      * target. For `Bound.Const` the target is applied immediately; for `Bound.Ref` the initial
      * orientation uses the signal's seed value (reactive updates follow via `subscribeRegions`).
      */
    private def aimedCamera(obj: js.Dynamic, lookAt: Bound[Three.Vec3])(using AllowUnsafe): js.Dynamic =
        // Unsafe: Camera.lookAt is a synchronous orientation setter on an already-positioned camera.
        val target = lookAt match
            case Bound.Const(v) => v
            case Bound.Ref(_)   => Three.Vec3.zero // initial seed; reactive update path re-aims on each emission
        val _ = obj.lookAt(target.x, target.y, target.z)
        obj
    end aimedCamera

    def makeMesh(geom: js.Dynamic, mat: js.Dynamic, m: Ast.Mesh)(using Frame): js.Dynamic < (Scope & Sync) =
        acquirePlain(positioned(jsNew(ThreeFacade.Mesh)(geom, mat), m.props.transform))

    def makeCustom(c: Ast.Custom[?])(using Frame): js.Dynamic < (Scope & Sync) =
        acquirePlain(buildCustom(c.build, c.input))

    /** Attaches a child object to a parent, outside the effect (mount-time wiring). */
    def attachUnsafe(parent: js.Dynamic, child: js.Dynamic)(using AllowUnsafe): Unit =
        // Unsafe: parent.add(child) is a synchronous scene-graph link on objects the reconciler owns.
        val _ = parent.add(child)

    /** Detaches a child object from a parent, outside the effect (reactive-region swap). */
    def detachUnsafe(parent: js.Dynamic, child: js.Dynamic)(using AllowUnsafe): Unit =
        // Unsafe: parent.remove(child) unlinks a child the reconciler owns when a reactive region swaps.
        val _ = parent.remove(child)

    // ---- raw bridge helpers (each // Unsafe: at its FFI seam) -----------------------------

    private def buildCustom[In](build: In => Any, input: In): js.Dynamic =
        // Unsafe: the sanctioned typed escape hatch; build produces the raw three.js object. The AST
        // types `build` as `In => Any` so it stays cross-platform (jvm/native never construct a Custom
        // node's live object); the client-side `Three.custom` factory takes `In => js.Dynamic`, so the
        // produced value is always a live three.js object here.
        build(input).asInstanceOf[js.Dynamic]

    private def boundColor(b: Bound[Three.Color]): Double = b match
        case Bound.Const(c) => c.packed.toDouble
        case Bound.Ref(_)   => 0xffffff.toDouble // seeded white; a reactive observe updates the live value

    private def boundNormal(b: Bound[Three.Normal]): Double = b match
        case Bound.Const(n) => n.toDouble
        case Bound.Ref(_)   => 1.0

    private def boundDouble(b: Bound[Double]): Double = b match
        case Bound.Const(d) => d
        case Bound.Ref(_)   => 1.0

    private def applyMaterialProps(m: js.Dynamic, color: Double, opacity: Double)(using AllowUnsafe): js.Dynamic =
        // Unsafe: setting initial material props on a freshly-constructed material.
        val _ = m.color.set(color)
        m.opacity = opacity
        m.transparent = opacity < 1.0
        m
    end applyMaterialProps

    private def applyStandard(m: Ast.Material.Standard)(using AllowUnsafe): js.Dynamic =
        val obj = applyMaterialProps(jsNew(ThreeFacade.MeshStandardMaterial)(), boundColor(m.color), boundNormal(m.opacity))
        // Unsafe: PBR props on the standard material.
        obj.metalness = boundNormal(m.metalness)
        obj.roughness = boundNormal(m.roughness)
        val _ = obj.emissive.set(boundColor(m.emissive))
        applyMap(obj, m.map)
    end applyStandard

    /** Sets a material's `map` from a loaded [[Three.Ast.Texture]] handle, resolving the live three.js
      * texture through the reconciler's [[Reconciler.TextureRegistry]] (populated by the `Three.texture`
      * loader). `Absent` leaves the material untextured; an unresolved handle leaves `map` unset so a
      * texture-less render still succeeds.
      */
    private def applyMap(m: js.Dynamic, map: Maybe[Bound[Ast.Texture]])(using AllowUnsafe): js.Dynamic =
        // Unsafe: a Bound.Const texture handle resolves to a live texture set on the material's map; a
        // Bound.Ref texture rides the reactive observe path, so only the constant handle is seeded here.
        map match
            case Present(Bound.Const(handle)) =>
                Reconciler.TextureRegistry.resolve(handle).foreach { tex =>
                    m.map = tex
                    m.needsUpdate = true
                }
                m
            case _ => m

    private def positioned(obj: js.Dynamic, t: Ast.Transform)(using AllowUnsafe): js.Dynamic =
        // Unsafe: seeding the initial transform; a reactive observe updates the live value for Bound.Ref props.
        t.position.foreach { case Bound.Const(v) => val _ = obj.position.set(v.x, v.y, v.z); case _ => () }
        t.rotation.foreach { case Bound.Const(v) => val _ = obj.rotation.set(v.x, v.y, v.z); case _ => () }
        t.scale.foreach { case Bound.Const(v) => val _ = obj.scale.set(v.x, v.y, v.z); case _ => () }
        obj
    end positioned

end ThreeFacadeOps
