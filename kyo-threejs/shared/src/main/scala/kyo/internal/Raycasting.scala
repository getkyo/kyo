package kyo.internal

import kyo.*
import scala.scalajs.js

/** Raycast hit resolution over the reconciler's live map: a `Raycaster` casts from the camera
  * through the pointer's normalized device coordinates and resolves the nearest `Live` object whose AST
  * node carries an `Interactive` handler, building the [[Pointer]] payload from the intersection.
  *
  * Raycasting runs headless against real three.js, so hit resolution is Node-testable; only the live
  * DOM pointer-event capture rides the mount's browser path.
  */
private[kyo] object Raycasting:

    /** Resolves the nearest interactive hit for a pointer at `ndc`, or `Absent` on a miss. */
    def hit(
        mounted: Reconciler.Mounted,
        camera: js.Dynamic,
        ndc: (Double, Double)
    )(using Frame): Maybe[(Reconciler.Live, Pointer)] < Sync =
        // Unsafe: a Raycaster cast over the live objects; all synchronous three.js reads, deferred.
        Sync.Unsafe.defer {
            discard(camera.updateMatrixWorld())
            val raycaster = js.Dynamic.newInstance(ThreeFacade.Raycaster)()
            val coords    = js.Dynamic.newInstance(ThreeFacade.Vector2)(ndc._1, ndc._2)
            discard(raycaster.setFromCamera(coords, camera))
            val targets = interactiveTargets(mounted)
            targets.foreach(t => discard(t.obj.updateMatrixWorld()))
            val targetObjs    = js.Array(targets.map(_.obj)*)
            val intersections = raycaster.intersectObjects(targetObjs, false).asInstanceOf[js.Array[js.Dynamic]]
            if intersections.length == 0 then Absent
            else
                val nearest = intersections(0)
                resolveLive(targets, nearest.`object`) match
                    case Present(live) => Present((live, toPointer(nearest, ndc, Pointer.Buttons.none)))
                    case Absent        => Absent
            end if
        }

    /** Builds the [[Pointer]] payload from an intersection (world point, distance, ndc, buttons). */
    def toPointer(intersection: js.Dynamic, ndc: (Double, Double), buttons: Pointer.Buttons)(using
        AllowUnsafe
    ): Pointer =
        // Unsafe: reading the intersection's world-space point and distance from the three.js result.
        val p        = intersection.point
        val point    = Vec3(p.x.asInstanceOf[Double], p.y.asInstanceOf[Double], p.z.asInstanceOf[Double])
        val distance = intersection.distance.asInstanceOf[Double]
        Pointer(point, distance, ndc, buttons)
    end toPointer

    /** The live objects whose AST node carries an `Interactive` handler. */
    private[kyo] def interactiveTargets(mounted: Reconciler.Mounted): Chunk[Reconciler.Live] =
        Chunk.from(mounted.live.values.filter { live =>
            live.node match
                case i: Three.Ast.Interactive =>
                    val p = i.meshProps
                    p.onClick.isDefined || p.onPointerOver.isDefined || p.onPointerOut.isDefined
                case _ => false
        })

    private def resolveLive(targets: Chunk[Reconciler.Live], obj: js.Dynamic)(using AllowUnsafe): Maybe[Reconciler.Live] =
        // Unsafe: reference-identity match of the hit three.js object against the live targets.
        Maybe.fromOption(targets.find(t => t.obj eq obj))

end Raycasting
