package kyo

import scala.scalajs.js

/** The scene-graph AST root: an immutable value describing a three.js scene, the 3D analog of the
  * kyo-ui `UI` tree.
  *
  * A scene is built from pure factory calls on the companion (`Three.scene`, `Three.mesh`,
  * `Three.Geometry.box`, `Three.Material.standard`, `Three.Light.ambient`), each allocating a plain
  * immutable case class and running no effects. The resulting value is shareable, re-renderable, and
  * Node-testable without a GPU. Transforms, materials, lights, and cameras carry internal [[Bound]]
  * props; the work of turning the value into live three.js objects happens at mount (`Three.runMount`)
  * or render (`Three.toImage`), never in the factories.
  */
sealed abstract class Three:
    private[kyo] def frame: Frame

// The 5 value types (Color/Vec3/Normal/Radians/Pointer) nest under `object Three` via a mixin trait
// per owning file (`ThreeColorOps` in Color.scala, etc.), so each stays byte-unchanged in its own
// file (ColorTest.scala/Vec3Test.scala/etc. stay valid 1:1-named test files, no renames) while
// resolving as `Three.Color`/`Three.Vec3`/etc. to every consumer.
object Three extends ThreeColorOps, ThreeVec3Ops, ThreeNormalOps, ThreeRadiansOps, ThreePointerOps:

    given CanEqual[Three, Three] = CanEqual.derived

    // ---- Scene-graph node factories ------------------------------------------------

    /** The render root holding the object hierarchy; one `Scene` per mount. */
    def scene(children: Three*)(using Frame): Ast.Scene =
        Ast.Scene(Ast.SceneProps(), Chunk.from(children))

    /** A transformable container with no geometry of its own; `.position`/`.rotation`/`.scale` move
      * every child as a unit. A `Group` is `Animated` so `.onFrame` advances a per-container
      * animation tick, mirroring the pattern available on `Mesh`.
      */
    def group(children: Three*)(using Frame): Ast.Group =
        Ast.Group(Ast.MeshProps(), Chunk.from(children))

    /** The renderable: a geometry paired with a material. Both are required, so a mesh cannot be
      * half-built. A `Mesh` is `Interactive` (raycast handlers) and `Animated` (`onFrame`).
      */
    def mesh(geometry: Ast.Geometry, material: Ast.Material)(using Frame): Ast.Mesh =
        Ast.Mesh(geometry, material, Ast.MeshProps(), Chunk.empty)

    /** The neutral node: an empty `Group`, the render-nothing branch for conditionals. */
    def empty(using Frame): Ast.Group =
        Ast.Group(Ast.MeshProps(), Chunk.empty)

    // ---- Reactive nodes ------------------------------------------------------------

    /** A subtree driven by a `Signal[Three]`: the bound region re-renders on each emission. Client-local
      * only (no `Schema`, no server drive): the signal's `Three` values are not serializable.
      */
    def reactive(signal: Signal[Three])(using Frame): Ast.Reactive = Ast.Reactive(signal, Absent)

    /** Shows `body` while `condition` holds, else `Three.empty`. Re-points to `render` (`Boolean` is
      * serializable, so `when` gains server drive too); the public signature is unchanged.
      */
    def when(condition: Signal[Boolean])(body: => Three)(using Frame): Ast.Reactive =
        condition.render(c => if c then body else empty)

    /** The typed raw-three.js escape hatch: `build` produces the live object the reconciler inserts.
      * `In` is the user's own typed parameter object. A `Custom` is `Interactive` and `Animated`.
      */
    def custom[In](build: In => js.Dynamic)(input: In)(using Frame): Ast.Custom[In] =
        Ast.Custom(build, input, Ast.MeshProps(), Chunk.empty)

    // ---- Orbit camera controls -----------------------------------------------------

    /** An orbit-camera control node: add `Three.controls(...)` to a scene and the client binds a three.js
      * `OrbitControls` instance to the live camera and canvas at mount, disposed on the mount `Scope`
      * close (no leaked listener). Drag orbits, scroll zooms, and
      * right-drag pans the camera around `target`; `autoRotate = true` spins the camera around the scene
      * automatically. Each flag toggles one OrbitControls affordance.
      *
      * Pure factory: the returned `Three.Ast.Controls` is an immutable AST value like any other node; the
      * imperative controls object is created at mount and never appears in the surface. A scene with no
      * `controls` node binds no controls (zero RAF cost).
      */
    def controls(
        target: Vec3 = Vec3.zero,
        enableZoom: Boolean = true,
        enablePan: Boolean = true,
        enableRotate: Boolean = true,
        autoRotate: Boolean = false
    )(using Frame): Ast.Controls =
        Ast.Controls(Bound.Const(target), enableZoom, enablePan, enableRotate, autoRotate)

    // ---- Signal extensions ---------------------------------------------------------

    extension [A](signal: Signal[A])
        /** Projects a value signal into a reactive subtree via `f`. Requires `Schema[A]` so the region
          * gains a server drive alongside its (unchanged) client-local behavior: a server-side `render`
          * re-renders the client's 3D subtree from the Schema-encoded data snapshot.
          */
        def render(f: A => Three)(using Frame, Schema[A]): Ast.Reactive =
            // The erased dataSignal's CanEqual[Any, Any] bound is not auto-derived under strict
            // equality; widened explicitly here, mirroring ReactiveUI.normalize's same erasure.
            given CanEqual[Any, Any] = CanEqual.derived
            Ast.Reactive(
                signal.map(f(_)),                   // client-local Signal[Three] (unchanged behaviour)
                Present(Ast.StructuralDrive.Render( // NEW server-drive half
                    dataSignal = signal.map(x => x: Any),
                    encode = a => Json.encode[A](a.asInstanceOf[A]),
                    decodeOne = s => f(Json.decode[A](s).getOrThrow) // re-render client-side
                ))
            )
    end extension

    extension [A](signal: Signal[Chunk[A]])
        /** One child node per element, reconciled by position. Requires `Schema[A]` so the region gains
          * a server drive: a server-side `foreach` splices/removes the client's 3D subtree from the
          * Schema-encoded chunk snapshot.
          */
        def foreach(render: A => Three)(using Frame, Schema[A]): Ast.Foreach[A] =
            Ast.Foreach(signal, Absent, (_, a) => render(a), foreachDrive(signal, Absent, (_, a) => render(a)))

        /** Keyed reconciliation so reorders and insertions reuse the matching live nodes (the GPU
          * buffers survive), important for 3D where recreating a mesh re-uploads buffers. Requires
          * `Schema[A]`, as [[foreach]] does.
          */
        def foreachKeyed(key: A => String)(render: A => Three)(using Frame, Schema[A]): Ast.Foreach[A] =
            Ast.Foreach(signal, Present(key), (_, a) => render(a), foreachDrive(signal, Present(key), (_, a) => render(a)))
    end extension

    // Builds the StructuralDrive.Foreach where A + Schema[A] are in scope (foreach/foreachKeyed's own
    // call site): encode Json-serializes the whole Chunk[A] snapshot; decodeKeyed decodes it back to
    // Chunk[A] then applies key/render right there (no erasure, no cast) to yield the keyed children.
    private def foreachDrive[A: Schema](
        signal: Signal[Chunk[A]],
        key: Maybe[A => String],
        render: (Int, A) => Three
    )(using Frame): Ast.StructuralDrive.Foreach =
        // See render's same erasure widening above.
        given CanEqual[Any, Any] = CanEqual.derived
        Ast.StructuralDrive.Foreach(
            dataSignal = signal.map(x => x: Any),
            encode = c => Json.encode[Chunk[A]](c.asInstanceOf[Chunk[A]]),
            decodeKeyed = s =>
                Json.decode[Chunk[A]](s).getOrThrow.zipWithIndex.map { (item, i) =>
                    (key.fold(i.toString)(_(item)), render(i, item))
                }
        )
    end foreachDrive

    // ---- Per-frame payload + the deterministic test driver --------------------------

    /** The per-frame payload an `onFrame` closure receives. */
    final case class Tick(elapsed: Duration, delta: Duration, frameIndex: Long) derives CanEqual

    /** The handle `ThreeFrames.Manual` yields so a test advances frames deterministically (no sleep). */
    trait Driver:
        /** Advances exactly one tick by `delta`, running each `onFrame` closure once then the single
          * render submit.
          */
        def step(delta: Duration)(using Frame): Unit < (Async & Abort[ThreeException])
    end Driver

    // ---- The AST node taxonomy ------------------------------------------------------------

    object Ast:

        /** The shared transform props every `Object3D` node carries; `Absent` means three.js default. */
        final private[kyo] case class Transform(
            position: Maybe[Bound[Vec3]] = Absent,
            rotation: Maybe[Bound[Vec3]] = Absent,
            scale: Maybe[Bound[Vec3]] = Absent
        )

        /** A `Mesh`/`Custom` props bundle: transform + interaction handlers + the frame hook, all
          * `Maybe`-defaulted.
          */
        final private[kyo] case class MeshProps(
            transform: Transform = Transform(),
            onClick: Maybe[Pointer => Any < Async] = Absent,
            onPointerOver: Maybe[Pointer => Any < Async] = Absent,
            onPointerOut: Maybe[Pointer => Any < Async] = Absent,
            onFrame: Maybe[Three.Tick => Any < Async] = Absent
        )

        /** The `Scene` props: transform + an optional background color. */
        final private[kyo] case class SceneProps(
            transform: Transform = Transform(),
            background: Maybe[Color] = Absent
        )

        /** The AST node base: every node carries a `Frame`, a child list, and a copy seam for setters. */
        sealed trait Node extends Three, UI.Ast.BackendNode:
            type Self <: Node
            def children: Chunk[Three]
            // The four BackendNode obligations, implemented ONCE here from existing data, all pure
            // Frame-free accessors (the kyo-threejs pure-AST invariant). Every Three.Ast.Node is a
            // first-class backend node the kyo-ui reactive walk descends natively; it is registry-
            // dispatched by `backend`, so there is no mount-closure obligation.
            final private[kyo] def backend: String = "three"
            // NOT a bare Chunk[+A] widen. `Three` is not a `UI` subtype, so `children: Chunk[Three]`
            // is not a `Chunk[UI]` by covariance; `Three`'s sole subtype is `Node`, which IS a `UI` (via
            // BackendNode), so each child projects by a total match over the sealed `Three` base.
            private[kyo] def backendChildren: Chunk[UI] = children.map { case n: Node =>
                n
            } // NOT final: Ast.Embed overrides it (its addressable child is the scene, not children)
            private[kyo] def placeholder: UI.Ast.BackendNode.Placeholder =
                UI.Ast.BackendNode.Placeholder("canvas", UI.Ast.Attrs()) // scene root only; inner nodes never SSR
                // NOT final: Ast.Embed overrides it to read its own settable `attrs` (`.id`, below).
            private[kyo] def boundProps: Chunk[UI.Ast.BackendNode.BoundProp] = Ast.boundPropsOf(this)
            // BackendNode.id's obligation, implemented ONCE as a no-op: HtmlRenderer.renderTo only ever
            // reads `.placeholder` off the ONE BackendNode reachable from a real UI tree (the scene root,
            // Ast.Embed; a nested Mesh/Group/etc. is walked by ReactiveUI.normalize for boundProps/
            // structural-region discovery, never for its own placeholder), so `.id` on any other Node is
            // dead by construction; a self-return keeps the chain typed without inventing behaviour that
            // never renders. Ast.Embed overrides it to actually set the placeholder's id.
            def id(v: String): Self = this.asInstanceOf[Self]
            // Decodes the wire Pointer once, walks this node's Three AST down relPath, runs the addressed
            // onClick. Pure server-side read of the same authoritative signals the structural region
            // drives; no live scene, no FFI.
            override private[kyo] def dispatchBackendEvent(relPath: Seq[String], encoded: String)(using Frame): Unit < Async =
                kyo.internal.PointerWire.decode(encoded) match
                    case Present(pointer) => resolveOnClick(this, relPath, pointer).map {
                            case Present(effect) => effect.unit
                            case Absent          => Kyo.unit
                        }
                    case Absent => Kyo.unit // malformed payload: drop (a buggy client must not tear the session down)
        end Node

        /** Anything with a transform: the `position`/`rotation`/`scale` setters route through a per-node
          * transform copy.
          */
        sealed trait Object3D extends Node:
            private[kyo] def transform: Transform
            private[kyo] def withTransform(t: Transform): Self

            /** Sets position from a static value. */
            def position(v: Vec3): Self = withTransform(transform.copy(position = Present(Bound.Const(v))))

            /** Sets position from a signal; the reconciler patches the live object on each emission. */
            def position(v: Signal[Vec3]): Self = withTransform(transform.copy(position = Present(Bound.Ref(v))))

            /** Internal setter accepting a pre-wrapped Bound; used by the factories and internal paths. */
            private[kyo] def positionBound(v: Bound[Vec3]): Self = withTransform(transform.copy(position = Present(v)))

            /** Sets Euler rotation from a static value (each component in radians). */
            def rotation(v: Vec3): Self = withTransform(transform.copy(rotation = Present(Bound.Const(v))))

            /** Sets rotation from a signal; the reconciler patches the live object on each emission. */
            def rotation(v: Signal[Vec3]): Self = withTransform(transform.copy(rotation = Present(Bound.Ref(v))))

            /** Internal setter accepting a pre-wrapped Bound. */
            private[kyo] def rotationBound(v: Bound[Vec3]): Self = withTransform(transform.copy(rotation = Present(v)))

            /** Sets scale from a static value. */
            def scale(v: Vec3): Self = withTransform(transform.copy(scale = Present(Bound.Const(v))))

            /** Sets scale from a signal; the reconciler patches the live object on each emission. */
            def scale(v: Signal[Vec3]): Self = withTransform(transform.copy(scale = Present(Bound.Ref(v))))

            /** Internal setter accepting a pre-wrapped Bound. */
            private[kyo] def scaleBound(v: Bound[Vec3]): Self = withTransform(transform.copy(scale = Present(v)))
        end Object3D

        /** A raycastable node: `onClick`/`onPointerOver`/`onPointerOut` store a `< Async` closure. */
        sealed trait Interactive extends Object3D:
            private[kyo] def meshProps: MeshProps
            private[kyo] def withMeshProps(p: MeshProps): Self

            /** Runs `f` with the raycast `Pointer` payload on click. */
            def onClick(f: Pointer => Any < Async): Self = withMeshProps(meshProps.copy(onClick = Present(f)))

            /** Pointer-enter (hover). */
            def onPointerOver(f: Pointer => Any < Async): Self =
                withMeshProps(meshProps.copy(onPointerOver = Present(f)))

            /** Pointer-leave. */
            def onPointerOut(f: Pointer => Any < Async): Self =
                withMeshProps(meshProps.copy(onPointerOut = Present(f)))
        end Interactive

        /** A frame-hooked node: `onFrame` runs every tick with the `Tick` payload. */
        sealed trait Animated extends Object3D:
            private[kyo] def meshProps: MeshProps
            private[kyo] def withMeshProps(p: MeshProps): Self

            /** Runs `f` every frame with the `Tick` payload. */
            def onFrame(f: Three.Tick => Any < Async): Self =
                withMeshProps(meshProps.copy(onFrame = Present(f)))
        end Animated

        // ---- Concrete container nodes -----------------------------------------------------

        final case class Scene(props: SceneProps, children: Chunk[Three])(using val frame: Frame)
            extends Object3D:
            type Self = Scene
            private[kyo] def transform: Transform               = props.transform
            private[kyo] def withTransform(t: Transform): Scene = copy(props = props.copy(transform = t))

            /** Sets the scene background clear color. */
            def background(c: Color): Scene = copy(props = props.copy(background = Present(c)))
        end Scene

        final case class Group(props: MeshProps, children: Chunk[Three])(using val frame: Frame)
            extends Object3D, Animated:
            type Self = Group
            private[kyo] def transform: Transform               = props.transform
            private[kyo] def withTransform(t: Transform): Group = copy(props = props.copy(transform = t))
            private[kyo] def meshProps: MeshProps               = props
            private[kyo] def withMeshProps(p: MeshProps): Group = copy(props = p)
        end Group

        final case class Mesh(
            geometry: Geometry,
            material: Material,
            props: MeshProps,
            children: Chunk[Three]
        )(using val frame: Frame) extends Interactive, Animated:
            type Self = Mesh
            private[kyo] def transform: Transform              = props.transform
            private[kyo] def withTransform(t: Transform): Mesh = copy(props = props.copy(transform = t))
            private[kyo] def meshProps: MeshProps              = props
            private[kyo] def withMeshProps(p: MeshProps): Mesh = copy(props = p)
        end Mesh

        final case class Custom[In](
            build: In => js.Dynamic,
            input: In,
            props: MeshProps,
            children: Chunk[Three]
        )(using val frame: Frame) extends Interactive, Animated:
            type Self = Custom[In]
            private[kyo] def transform: Transform                    = props.transform
            private[kyo] def withTransform(t: Transform): Custom[In] = copy(props = props.copy(transform = t))
            private[kyo] def meshProps: MeshProps                    = props
            private[kyo] def withMeshProps(p: MeshProps): Custom[In] = copy(props = p)
        end Custom

        // ---- Reactive nodes ---------------------------------------------------------------

        final case class Reactive(
            signal: Signal[Three],
            // Absent for raw reactive(Signal[Three]) (client-local only); Present for render[A]/when.
            private[kyo] serverDrive: Maybe[Ast.StructuralDrive.Render] = Absent
        )(using val frame: Frame) extends Node:
            type Self = Reactive
            def children: Chunk[Three] = Chunk.empty
            override private[kyo] def structuralRegion: Maybe[UI.Ast.BackendNode.StructuralBinding] =
                serverDrive.map(sd => UI.Ast.BackendNode.StructuralBinding(sd.dataSignal, sd.encode))
            private[kyo] def decodeSubtree(encoded: String): Maybe[Three] =
                serverDrive.map(_.decodeOne(encoded))
        end Reactive

        final case class Foreach[A](
            signal: Signal[Chunk[A]],
            key: Maybe[A => String],
            render: (Int, A) => Three,
            // Present for every foreach/foreachKeyed (they now capture Schema[A]); the FOREACH drive
            // variant carries the WHOLE keyed decode captured where A + Schema[A] are in scope.
            private[kyo] serverDrive: Ast.StructuralDrive.Foreach
        )(using val frame: Frame) extends Node:
            type Self = Foreach[A]
            def children: Chunk[Three] = Chunk.empty
            override private[kyo] def structuralRegion: Maybe[UI.Ast.BackendNode.StructuralBinding] =
                // See Three.render's same erasure widening (Three.scala, the render extension).
                given CanEqual[Any, Any] = CanEqual.derived
                Present(UI.Ast.BackendNode.StructuralBinding(signal.map(x => x: Any), serverDrive.encode))
            end structuralRegion
            // The CLIENT half: decode the wire snapshot straight to keyed Three children (the drive variant
            // captured `key`/`render` at construction, so this is a delegation with no erasure and no cast).
            private[kyo] def decodeKeyed(encoded: String): Chunk[(String, Three)] = serverDrive.decodeKeyed(encoded)
        end Foreach

        // The pure, FFI-free wire-codec bundle a structural region captures at construction, where
        // Schema[A] is in scope. dataSignal is the type-erased DATA signal the SERVER observes; encode
        // serializes an emission; the decode side re-hydrates on the CLIENT. No FFI, no js.Dynamic: lives
        // in shared/src/main. private[kyo].
        private[kyo] object StructuralDrive:
            final private[kyo] case class Render(
                dataSignal: Signal[Any],   // Signal[A] widened
                encode: Any => String,     // Json.encode[A] under the captured Schema
                decodeOne: String => Three // s => f(Json.decode[A](s)): re-render one subtree client-side
            )
            final private[kyo] case class Foreach(
                dataSignal: Signal[Any],                      // Signal[Chunk[A]] widened
                encode: Any => String,                        // Json.encode[Chunk[A]] under the captured Schema
                decodeKeyed: String => Chunk[(String, Three)] // decode Chunk[A] then apply key/render, in scope
            )
        end StructuralDrive

        // ---- Orbit controls node ----------------------------------------------------------

        /** The immutable AST value for orbit camera control, produced by [[Three.controls]]. The client
          * binds a live three.js `OrbitControls` instance from it at mount over the live camera and canvas,
          * disposed on the mount `Scope` close. It renders no
          * object of its own (it controls the camera), so it carries no live three.js object and no
          * children; the reconciler records it as an empty holder the mount pipeline reads to construct the
          * controls binding.
          */
        final case class Controls(
            target: Bound[Vec3],
            enableZoom: Boolean,
            enablePan: Boolean,
            enableRotate: Boolean,
            autoRotate: Boolean
        )(using val frame: Frame) extends Node:
            type Self = Controls
            def children: Chunk[Three] = Chunk.empty
        end Controls

        // The embed carrier: a private backend node bundling the scene + camera + frames so the
        // registry-dispatched ThreeBackend.mount conveys them, while Three.embed returns a BackendNode.
        // backendChildren is the scene so the kyo-ui reactive walk descends the scene's
        // boundProps; registry-dispatched by `backend = "three"` (no mount-closure member).
        // private[kyo]: not public surface.
        final private[kyo] case class Embed(
            scene: Three,
            camera: Three.Ast.Camera,
            frames: ThreeFrames,
            attrs: UI.Ast.Attrs = UI.Ast.Attrs()
        )(using val frame: Frame)
            extends Node:
            type Self = Embed
            def children: Chunk[Three] = Chunk.empty
            // `scene` is a `Three` (sole subtype `Node`, a `UI` via BackendNode).
            override private[kyo] def backendChildren: Chunk[UI]                      = Chunk(scene).map { case n: Node => n }
            override private[kyo] def boundProps: Chunk[UI.Ast.BackendNode.BoundProp] = Chunk.empty
            // Embed is the one Node the kyo-ui reactive walk SSRs a placeholder tag for (the scene root,
            // Node.placeholder's own comment); it reads its own settable `attrs` rather than the base's
            // hardcoded empty one, so `.id` below actually reaches the rendered `<canvas>`.
            override private[kyo] def placeholder: UI.Ast.BackendNode.Placeholder =
                UI.Ast.BackendNode.Placeholder("canvas", attrs)
            // Embed cannot mix Element/Inline to inherit Element.id (Element.children: Chunk[UI] collides
            // with Node.children: Chunk[Three], since Three is not a UI; the design doc's rationale for
            // dropping Inline from BackendNode). Overrides BackendNode.id directly against its own
            // placeholder's attrs, the same call-site shape as Element.id with no such collision.
            override def id(v: String): Embed = copy(attrs = attrs.copy(identifier = Present(v)))
            // Touches ThreeBackend so its object initializer (which self-registers into the client
            // Backend registry, mirroring how DomBackend pre-registers) runs at construction time,
            // guaranteeing registration before ANY later dispatch path (UI.runMount ->
            // DomBackend.fireHostMounts -> Backend.lookup, or a hydrate-only bootstrap) looks it up by
            // key. Every Three.embed call constructs an Embed, so this covers every mount path
            // uniformly; ThreeMount.runMount's own dispatch calls ThreeBackend.mount directly and needs
            // no such touch, but nothing else in the tree does.
            kyo.internal.ThreeBackend.ensureRegistered()
        end Embed

        // ---- The Geometry sealed union --------------------------------------------

        sealed trait Geometry derives CanEqual
        object Geometry:
            final case class Box(width: Double, height: Double, depth: Double)(using val frame: Frame)
                extends Geometry
            final case class Sphere(radius: Double, widthSegments: Int, heightSegments: Int)(using val frame: Frame)
                extends Geometry
            final case class Plane(width: Double, height: Double)(using val frame: Frame) extends Geometry
            final case class Cylinder(radiusTop: Double, radiusBottom: Double, height: Double, radialSegments: Int)(
                using val frame: Frame
            ) extends Geometry
            final case class Cone(radius: Double, height: Double, radialSegments: Int)(using val frame: Frame)
                extends Geometry
            final case class Torus(radius: Double, tube: Double, radialSegments: Int, tubularSegments: Int)(
                using val frame: Frame
            ) extends Geometry
            final case class Custom[In](build: In => js.Dynamic, input: In)(using val frame: Frame)
                extends Geometry
        end Geometry

        // ---- The Material sealed union --------------------------------------------

        sealed trait Material derives CanEqual
        object Material:
            final case class Basic(
                color: Bound[Color],
                opacity: Bound[Normal],
                map: Maybe[Bound[Texture]]
            )(using val frame: Frame) extends Material:
                /** Sets color from a static value. */
                def color(v: Color): Basic = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live material on each emission. */
                def color(v: Signal[Color]): Basic = copy(color = Bound.Ref(v))

                /** Sets opacity from a static value. */
                def opacity(v: Normal): Basic = copy(opacity = Bound.Const(v))

                /** Sets opacity from a signal; the reconciler patches the live material on each emission. */
                def opacity(v: Signal[Normal]): Basic = copy(opacity = Bound.Ref(v))

                /** Sets the texture map from a loaded handle. */
                def map(v: Texture): Basic = copy(map = Present(Bound.Const(v)))
            end Basic

            final case class Standard(
                color: Bound[Color],
                metalness: Bound[Normal],
                roughness: Bound[Normal],
                opacity: Bound[Normal],
                map: Maybe[Bound[Texture]],
                emissive: Bound[Color]
            )(using val frame: Frame) extends Material:
                /** Sets color from a static value. */
                def color(v: Color): Standard = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live material on each emission. */
                def color(v: Signal[Color]): Standard = copy(color = Bound.Ref(v))

                /** Sets metalness from a static value. */
                def metalness(v: Normal): Standard = copy(metalness = Bound.Const(v))

                /** Sets metalness from a signal; the reconciler patches the live material on each emission. */
                def metalness(v: Signal[Normal]): Standard = copy(metalness = Bound.Ref(v))

                /** Sets roughness from a static value. */
                def roughness(v: Normal): Standard = copy(roughness = Bound.Const(v))

                /** Sets roughness from a signal; the reconciler patches the live material on each emission. */
                def roughness(v: Signal[Normal]): Standard = copy(roughness = Bound.Ref(v))

                /** Sets opacity from a static value. */
                def opacity(v: Normal): Standard = copy(opacity = Bound.Const(v))

                /** Sets opacity from a signal; the reconciler patches the live material on each emission. */
                def opacity(v: Signal[Normal]): Standard = copy(opacity = Bound.Ref(v))

                /** Sets the texture map from a loaded handle. */
                def map(v: Texture): Standard = copy(map = Present(Bound.Const(v)))

                /** Sets emissive color from a static value. */
                def emissive(v: Color): Standard = copy(emissive = Bound.Const(v))

                /** Sets emissive color from a signal; the reconciler patches the live material on each emission. */
                def emissive(v: Signal[Color]): Standard = copy(emissive = Bound.Ref(v))
            end Standard

            final case class Line(color: Bound[Color], opacity: Bound[Normal])(using val frame: Frame)
                extends Material:
                /** Sets color from a static value. */
                def color(v: Color): Line = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live material on each emission. */
                def color(v: Signal[Color]): Line = copy(color = Bound.Ref(v))

                /** Sets opacity from a static value. */
                def opacity(v: Normal): Line = copy(opacity = Bound.Const(v))

                /** Sets opacity from a signal; the reconciler patches the live material on each emission. */
                def opacity(v: Signal[Normal]): Line = copy(opacity = Bound.Ref(v))
            end Line

            final case class Points(color: Bound[Color], size: Double, opacity: Bound[Normal])(
                using val frame: Frame
            ) extends Material:
                /** Sets color from a static value. */
                def color(v: Color): Points = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live material on each emission. */
                def color(v: Signal[Color]): Points = copy(color = Bound.Ref(v))

                /** Sets opacity from a static value. */
                def opacity(v: Normal): Points = copy(opacity = Bound.Const(v))

                /** Sets opacity from a signal; the reconciler patches the live material on each emission. */
                def opacity(v: Signal[Normal]): Points = copy(opacity = Bound.Ref(v))
            end Points

            final case class Custom[In](build: In => js.Dynamic, input: In)(using val frame: Frame)
                extends Material
        end Material

        // ---- The Light sealed union -----------------------------------------------

        sealed trait Light extends Object3D derives CanEqual:
            type Self <: Light
            def children: Chunk[Three] = Chunk.empty
        object Light:
            final case class Ambient(color: Bound[Color], intensity: Bound[Double], props: Transform)(
                using val frame: Frame
            ) extends Light:
                type Self = Ambient
                private[kyo] def transform: Transform                 = props
                private[kyo] def withTransform(t: Transform): Ambient = copy(props = t)

                /** Sets color from a static value. */
                def color(v: Color): Ambient = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live light on each emission. */
                def color(v: Signal[Color]): Ambient = copy(color = Bound.Ref(v))

                /** Sets intensity from a static value. */
                def intensity(v: Double): Ambient = copy(intensity = Bound.Const(v))

                /** Sets intensity from a signal; the reconciler patches the live light on each emission. */
                def intensity(v: Signal[Double]): Ambient = copy(intensity = Bound.Ref(v))
            end Ambient

            final case class Directional(
                color: Bound[Color],
                intensity: Bound[Double],
                props: Transform
            )(using val frame: Frame) extends Light:
                type Self = Directional
                private[kyo] def transform: Transform                     = props
                private[kyo] def withTransform(t: Transform): Directional = copy(props = t)

                /** Sets color from a static value. */
                def color(v: Color): Directional = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live light on each emission. */
                def color(v: Signal[Color]): Directional = copy(color = Bound.Ref(v))

                /** Sets intensity from a static value. */
                def intensity(v: Double): Directional = copy(intensity = Bound.Const(v))

                /** Sets intensity from a signal; the reconciler patches the live light on each emission. */
                def intensity(v: Signal[Double]): Directional = copy(intensity = Bound.Ref(v))
            end Directional

            final case class Point(
                color: Bound[Color],
                intensity: Bound[Double],
                distance: Double,
                props: Transform
            )(using val frame: Frame) extends Light:
                type Self = Point
                private[kyo] def transform: Transform               = props
                private[kyo] def withTransform(t: Transform): Point = copy(props = t)

                /** Sets color from a static value. */
                def color(v: Color): Point = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live light on each emission. */
                def color(v: Signal[Color]): Point = copy(color = Bound.Ref(v))

                /** Sets intensity from a static value. */
                def intensity(v: Double): Point = copy(intensity = Bound.Const(v))

                /** Sets intensity from a signal; the reconciler patches the live light on each emission. */
                def intensity(v: Signal[Double]): Point = copy(intensity = Bound.Ref(v))
            end Point

            final case class Spot(
                color: Bound[Color],
                intensity: Bound[Double],
                angle: Radians,
                penumbra: Normal,
                props: Transform
            )(using val frame: Frame) extends Light:
                type Self = Spot
                private[kyo] def transform: Transform              = props
                private[kyo] def withTransform(t: Transform): Spot = copy(props = t)

                /** Sets color from a static value. */
                def color(v: Color): Spot = copy(color = Bound.Const(v))

                /** Sets color from a signal; the reconciler patches the live light on each emission. */
                def color(v: Signal[Color]): Spot = copy(color = Bound.Ref(v))

                /** Sets intensity from a static value. */
                def intensity(v: Double): Spot = copy(intensity = Bound.Const(v))

                /** Sets intensity from a signal; the reconciler patches the live light on each emission. */
                def intensity(v: Signal[Double]): Spot = copy(intensity = Bound.Ref(v))
            end Spot

            final case class Hemisphere(
                sky: Bound[Color],
                ground: Bound[Color],
                intensity: Bound[Double],
                props: Transform
            )(using val frame: Frame) extends Light:
                type Self = Hemisphere
                private[kyo] def transform: Transform                    = props
                private[kyo] def withTransform(t: Transform): Hemisphere = copy(props = t)

                /** Sets sky color from a static value. */
                def sky(v: Color): Hemisphere = copy(sky = Bound.Const(v))

                /** Sets sky color from a signal; the reconciler patches the live light on each emission. */
                def sky(v: Signal[Color]): Hemisphere = copy(sky = Bound.Ref(v))

                /** Sets ground color from a static value. */
                def ground(v: Color): Hemisphere = copy(ground = Bound.Const(v))

                /** Sets ground color from a signal; the reconciler patches the live light on each emission. */
                def ground(v: Signal[Color]): Hemisphere = copy(ground = Bound.Ref(v))

                /** Sets intensity from a static value. */
                def intensity(v: Double): Hemisphere = copy(intensity = Bound.Const(v))

                /** Sets intensity from a signal; the reconciler patches the live light on each emission. */
                def intensity(v: Signal[Double]): Hemisphere = copy(intensity = Bound.Ref(v))
            end Hemisphere
        end Light

        // ---- The Camera sealed union ----------------------------------------------

        sealed trait Camera extends Object3D derives CanEqual:
            type Self <: Camera
            def children: Chunk[Three] = Chunk.empty
        object Camera:
            final case class Perspective(
                fov: Radians,
                near: Double,
                far: Double,
                lookAt: Bound[Vec3],
                props: Transform
            )(using val frame: Frame) extends Camera:
                type Self = Perspective
                private[kyo] def transform: Transform                     = props
                private[kyo] def withTransform(t: Transform): Perspective = copy(props = t)

                /** Sets lookAt from a static target point. */
                def lookAt(v: Vec3): Perspective = copy(lookAt = Bound.Const(v))

                /** Sets lookAt from a signal; the reconciler re-aims the camera on each emission. */
                def lookAt(v: Signal[Vec3]): Perspective = copy(lookAt = Bound.Ref(v))
            end Perspective

            final case class Orthographic(
                viewSize: Double,
                near: Double,
                far: Double,
                lookAt: Bound[Vec3],
                props: Transform
            )(using val frame: Frame) extends Camera:
                type Self = Orthographic
                private[kyo] def transform: Transform                      = props
                private[kyo] def withTransform(t: Transform): Orthographic = copy(props = t)

                /** Sets lookAt from a static target point. */
                def lookAt(v: Vec3): Orthographic = copy(lookAt = Bound.Const(v))

                /** Sets lookAt from a signal; the reconciler re-aims the camera on each emission. */
                def lookAt(v: Signal[Vec3]): Orthographic = copy(lookAt = Bound.Ref(v))
            end Orthographic
        end Camera

        // ---- Texture handle -------------------------------------------------------

        /** A Scope-managed GPU texture handle the reconciler loads and disposes. */
        sealed trait Texture derives CanEqual
        object Texture:
            final case class FromUrl(url: String) extends Texture
        end Texture

        // ---- Server-side bound-prop discovery --------------------------------------

        // Pure, FFI-free (no js.Dynamic) mirror of ThreeMount.extractBoundRefs (the client-side live-
        // setter half): walks the SAME Bound.Ref detection over the SAME 12-key closed vocabulary
        // (position/rotation/scale, material.color/opacity/metalness/roughness/emissive,
        // color/groundColor/intensity, lookAt), yielding (key, signal, encode) triples instead of live
        // setters. The server-side half of the `boundProps` SPI member (Node, above); `encode`
        // Json-serializes each raw value under its Schema so the wire carries only encoded data, never
        // a closure or a js.Dynamic. Scene.props.background and every node's own transform (the
        // carrier, as opposed to its position/rotation/scale fields) are NOT in the 12-key vocabulary
        // and fall to the catch-all: a deliberate scope boundary, not a gap. private[kyo], not public.
        private[kyo] def boundPropsOf(node: Node): Chunk[UI.Ast.BackendNode.BoundProp] =
            // The BackendNode.boundProps SPI member (UI.scala) carries no Frame; encode is a pure
            // Json-encoding of an already-computed value, not a user call site, so Frame.internal is
            // the sanctioned zero-derivation frame (matches Async.scala/Layer.scala's own use).
            given Frame = Frame.internal
            var buf     = Chunk.empty[UI.Ast.BackendNode.BoundProp]

            def add[A](key: String, signal: Signal[A], encode: A => String): Unit =
                buf = buf.appended(UI.Ast.BackendNode.BoundProp(
                    key,
                    signal.asInstanceOf[Signal[Any]],
                    encode.asInstanceOf[Any => String]
                ))

            def addColor(key: String, b: Bound[Color]): Unit =
                b match
                    case Bound.Ref(sig) => add(key, sig, (c: Color) => Json.encode[Int](c.packed))
                    case _              => ()

            def addNormal(key: String, b: Bound[Normal]): Unit =
                b match
                    case Bound.Ref(sig) => add(key, sig, (n: Normal) => Json.encode[Double](n.toDouble))
                    case _              => ()

            def addDouble(key: String, b: Bound[Double]): Unit =
                b match
                    case Bound.Ref(sig) => add(key, sig, (d: Double) => Json.encode[Double](d))
                    case _              => ()

            def addVec3(key: String, b: Maybe[Bound[Vec3]]): Unit =
                b.foreach {
                    case Bound.Ref(sig) => add(key, sig, (v: Vec3) => Json.encode[Vec3](v))
                    case _              => ()
                }

            node match
                case m: Mesh =>
                    addVec3("position", m.props.transform.position)
                    addVec3("rotation", m.props.transform.rotation)
                    addVec3("scale", m.props.transform.scale)
                    m.material match
                        case mat: Material.Basic =>
                            addColor("material.color", mat.color)
                            addNormal("material.opacity", mat.opacity)
                        case mat: Material.Standard =>
                            addColor("material.color", mat.color)
                            addNormal("material.opacity", mat.opacity)
                            addNormal("material.metalness", mat.metalness)
                            addNormal("material.roughness", mat.roughness)
                            addColor("material.emissive", mat.emissive)
                        case mat: Material.Line =>
                            addColor("material.color", mat.color)
                            addNormal("material.opacity", mat.opacity)
                        case mat: Material.Points =>
                            addColor("material.color", mat.color)
                            addNormal("material.opacity", mat.opacity)
                        case _ => ()
                    end match
                case c: Custom[?] =>
                    addVec3("position", c.props.transform.position)
                    addVec3("rotation", c.props.transform.rotation)
                    addVec3("scale", c.props.transform.scale)
                case l: Light.Ambient =>
                    addColor("color", l.color)
                    addDouble("intensity", l.intensity)
                case l: Light.Directional =>
                    addColor("color", l.color)
                    addDouble("intensity", l.intensity)
                    addVec3("position", l.props.position)
                case l: Light.Point =>
                    addColor("color", l.color)
                    addDouble("intensity", l.intensity)
                    addVec3("position", l.props.position)
                case l: Light.Spot =>
                    addColor("color", l.color)
                    addDouble("intensity", l.intensity)
                    addVec3("position", l.props.position)
                case l: Light.Hemisphere =>
                    addColor("color", l.sky)
                    addColor("groundColor", l.ground)
                    addDouble("intensity", l.intensity)
                case g: Group =>
                    addVec3("position", g.props.transform.position)
                    addVec3("rotation", g.props.transform.rotation)
                    addVec3("scale", g.props.transform.scale)
                case cam: Camera.Perspective =>
                    addVec3("position", cam.transform.position)
                    cam.lookAt match
                        case Bound.Ref(sig) => add("lookAt", sig, (v: Vec3) => Json.encode[Vec3](v))
                        case _              => ()
                case cam: Camera.Orthographic =>
                    addVec3("position", cam.transform.position)
                    cam.lookAt match
                        case Bound.Ref(sig) => add("lookAt", sig, (v: Vec3) => Json.encode[Vec3](v))
                        case _              => ()
                case _ => ()
            end match

            buf
        end boundPropsOf

    end Ast

    // Mirrors ReactiveUI.targetSatisfies member for member. NODE-first: a Reactive is matched BEFORE
    // any relPath split and resolves its current content at the SAME path (the boundary consumes no
    // segment); a Foreach child is addressed by key through signal.current + render(i, item); a static
    // container's children are index-addressed; at relPath == empty an Interactive node's onClick
    // applies to the pointer. Matching Reactive only under `seg +: rest` would DROP a click landing
    // DIRECTLY on render/when content (relPath == empty); matching the node first is what makes that
    // corner resolve.
    private def resolveOnClick(node: Three, relPath: Seq[String], pointer: Pointer)(using Frame): Maybe[Any < Async] < Async =
        node match
            case r: Ast.Reactive =>
                // Path-transparent: content occupies the boundary's OWN path (no segment consumed), so
                // recurse into the CURRENT content at the SAME relPath, read at click time. Matched
                // BEFORE the relPath split so it resolves whether relPath is empty (click on render
                // content) or not (render content wraps a deeper target).
                r.signal.current.map(cur => resolveOnClick(cur, relPath, pointer))
            case f: Ast.Foreach[a] =>
                relPath match
                    case seg +: rest =>
                        f.signal.current.map { items =>
                            val idx = f.key.fold(seg.toIntOption.getOrElse(-1))(k => items.indexWhere(it => k(it) == seg))
                            if idx >= 0 && idx < items.size then resolveOnClick(f.render(idx, items(idx)), rest, pointer)
                            else Absent
                        }
                    case Seq() => Absent // the Foreach holder carries no onClick of its own
            case e: Ast.Embed =>
                // The Embed carrier's ADDRESSABLE child is backendChildren(0) == scene; its `children` is
                // EMPTY, so the generic Ast.Node arm below would index e.children -> out of range ->
                // Absent, silently DROPPING every server-mode 3D click through the embed hop. A click
                // arriving at the Embed carries relPath ["0", ...] (the scene is materialized/indexed at
                // path :+ "0"; the ReactiveUI backendChildren descent puts it at path :+ "0"), so map the
                // leading "0" segment to the scene and continue the walk there. Matched BEFORE the generic
                // Ast.Node arm.
                relPath match
                    case "0" +: rest => resolveOnClick(e.scene, rest, pointer)
                    case _           => Absent // no other child index exists on an Embed
            case n: Ast.Node =>
                relPath match
                    case Seq() => n match
                            case i: Ast.Interactive => i.meshProps.onClick.map(f => f(pointer))
                            case _                  => Absent
                    case seg +: rest => // static container: index-address a child, then descend (nested Interactive descendants included)
                        Maybe.fromOption(seg.toIntOption).filter(i => i >= 0 && i < n.children.size)
                            .fold(Absent: Maybe[Any < Async] < Async)(i => resolveOnClick(n.children(i), rest, pointer))

    // ---- Geometry factories --------------------------------------------------------

    object Geometry:
        def box(width: Double = 1.0, height: Double = 1.0, depth: Double = 1.0)(using Frame): Ast.Geometry.Box =
            Ast.Geometry.Box(width, height, depth)
        def sphere(radius: Double = 1.0, widthSegments: Int = 32, heightSegments: Int = 16)(using Frame): Ast.Geometry.Sphere =
            Ast.Geometry.Sphere(radius, widthSegments, heightSegments)
        def plane(width: Double = 1.0, height: Double = 1.0)(using Frame): Ast.Geometry.Plane =
            Ast.Geometry.Plane(width, height)
        def cylinder(radiusTop: Double = 1.0, radiusBottom: Double = 1.0, height: Double = 1.0, radialSegments: Int = 32)(
            using Frame
        ): Ast.Geometry.Cylinder =
            Ast.Geometry.Cylinder(radiusTop, radiusBottom, height, radialSegments)
        def cone(radius: Double = 1.0, height: Double = 1.0, radialSegments: Int = 32)(using Frame): Ast.Geometry.Cone =
            Ast.Geometry.Cone(radius, height, radialSegments)
        def torus(radius: Double = 1.0, tube: Double = 0.4, radialSegments: Int = 16, tubularSegments: Int = 96)(
            using Frame
        ): Ast.Geometry.Torus =
            Ast.Geometry.Torus(radius, tube, radialSegments, tubularSegments)
        def custom[In](build: In => js.Dynamic)(input: In)(using Frame): Ast.Geometry.Custom[In] =
            Ast.Geometry.Custom(build, input)
    end Geometry

    // ---- Material factories --------------------------------------------------------

    object Material:
        def basic(
            color: Color = Color.white,
            opacity: Normal = Normal.one,
            map: Maybe[Ast.Texture] = Absent
        )(using Frame): Ast.Material.Basic =
            Ast.Material.Basic(Bound.Const(color), Bound.Const(opacity), map.map(Bound.Const(_)))
        def standard(
            color: Color = Color.white,
            metalness: Normal = Normal.zero,
            roughness: Normal = Normal.one,
            opacity: Normal = Normal.one,
            map: Maybe[Ast.Texture] = Absent,
            emissive: Color = Color.black
        )(using Frame): Ast.Material.Standard =
            Ast.Material.Standard(
                Bound.Const(color),
                Bound.Const(metalness),
                Bound.Const(roughness),
                Bound.Const(opacity),
                map.map(Bound.Const(_)),
                Bound.Const(emissive)
            )
        def line(
            color: Color = Color.white,
            opacity: Normal = Normal.one
        )(using Frame): Ast.Material.Line =
            Ast.Material.Line(Bound.Const(color), Bound.Const(opacity))
        def points(
            color: Color = Color.white,
            size: Double = 1.0,
            opacity: Normal = Normal.one
        )(using Frame): Ast.Material.Points =
            Ast.Material.Points(Bound.Const(color), size, Bound.Const(opacity))
        def custom[In](build: In => js.Dynamic)(input: In)(using Frame): Ast.Material.Custom[In] =
            Ast.Material.Custom(build, input)
    end Material

    // ---- Light factories -----------------------------------------------------------

    object Light:
        def ambient(
            color: Color = Color.white,
            intensity: Double = 1.0
        )(using Frame): Ast.Light.Ambient =
            Ast.Light.Ambient(Bound.Const(color), Bound.Const(intensity), Ast.Transform())
        def directional(
            color: Color = Color.white,
            intensity: Double = 1.0,
            position: Vec3 = Vec3.one
        )(using Frame): Ast.Light.Directional =
            Ast.Light.Directional(Bound.Const(color), Bound.Const(intensity), Ast.Transform(position = Present(Bound.Const(position))))
        def point(
            color: Color = Color.white,
            intensity: Double = 1.0,
            distance: Double = 0.0,
            position: Vec3 = Vec3.zero
        )(using Frame): Ast.Light.Point =
            Ast.Light.Point(Bound.Const(color), Bound.Const(intensity), distance, Ast.Transform(position = Present(Bound.Const(position))))
        def spot(
            color: Color = Color.white,
            intensity: Double = 1.0,
            angle: Radians = Radians.deg(60),
            penumbra: Normal = Normal.zero,
            position: Vec3 = Vec3.zero
        )(using Frame): Ast.Light.Spot =
            Ast.Light.Spot(
                Bound.Const(color),
                Bound.Const(intensity),
                angle,
                penumbra,
                Ast.Transform(position = Present(Bound.Const(position)))
            )
        def hemisphere(
            sky: Color = Color.white,
            ground: Color = Color.black,
            intensity: Double = 1.0
        )(using Frame): Ast.Light.Hemisphere =
            Ast.Light.Hemisphere(Bound.Const(sky), Bound.Const(ground), Bound.Const(intensity), Ast.Transform())
    end Light

    // ---- Camera factories ----------------------------------------------------------

    object Camera:
        def perspective(
            fov: Radians = Radians.deg(75),
            near: Double = 0.1,
            far: Double = 1000.0,
            position: Vec3 = Vec3(0, 0, 5),
            lookAt: Vec3 = Vec3.zero
        )(using Frame): Ast.Camera.Perspective =
            Ast.Camera.Perspective(fov, near, far, Bound.Const(lookAt), Ast.Transform(position = Present(Bound.Const(position))))
        def orthographic(
            viewSize: Double = 10.0,
            near: Double = 0.1,
            far: Double = 1000.0,
            position: Vec3 = Vec3(0, 0, 5),
            lookAt: Vec3 = Vec3.zero
        )(using Frame): Ast.Camera.Orthographic =
            Ast.Camera.Orthographic(viewSize, near, far, Bound.Const(lookAt), Ast.Transform(position = Present(Bound.Const(position))))
    end Camera

    // ---- Runner delegates (reachable via `import kyo.*`) ---------------------------
    // These members delegate to `ThreeMount`, which holds the implementation bodies.
    // Object members carry no package-level symbol, so `Three.runMount` and
    // `UI.runMount` (a top-level extension in kyo-ui) coexist with no Scala.js linker clash.

    /** Mounts `scene` into the canvas at `selector` and runs the frame loop until the scope
      * closes.
      *
      * Resolves the `<canvas>` at `selector`, acquires a `WebGLRenderer` under `Scope`,
      * materializes the scene, subscribes one observe fiber per reactive prop, wires pointer
      * delegation, and runs the frame loop. The whole effect is `< (Async & Scope &
      * Abort[ThreeException])`: teardown disposes every GPU resource, and a missing canvas or
      * absent WebGL context surfaces as a typed `ThreeException` through `Abort`.
      */
    def runMount(
        scene: Three,
        camera: Three.Ast.Camera,
        selector: String,
        frames: ThreeFrames = ThreeFrames.Raf
    )(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.runMount(scene, camera, selector, frames)

    /** Yields a deterministic [[Three.Driver]] over the materialized scene, the same driver the
      * `ThreeFrames.Manual` path yields; a test steps frames without any sleep.
      *
      * The headless materialize runs no WebGL and no canvas; the driver's `.step(delta)` advances
      * exactly one tick so assertions observe mutations before `step` returns.
      */
    def testDriver(
        scene: Three.Ast.Scene,
        camera: Three.Ast.Camera
    )(using Frame): Three.Driver < (Async & Scope) =
        ThreeMount.testDriver(scene, camera)

    /** Loads a glTF/GLB at `url` into an [[Asset.Gltf]] subtree; Scope-managed, typed failure on
      * load error.
      */
    def loadGltf(url: String)(using Frame): Asset.Gltf < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.loadGltf(url)

    /** Loads an image at `url` into a GPU [[Three.Ast.Texture]] handle for a material `map`;
      * Scope-managed (the texture disposes on scope close), typed failure on load error.
      */
    def texture(url: String)(using Frame): Three.Ast.Texture < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.texture(url)

    /** Renders `scene` from `camera` to a `width`x`height` PNG, returning the kyo-browser
      * [[kyo.internal.Image]].
      *
      * Materializes the scene headless (no live mount, no frame loop), fills every reactive prop
      * and structural reactive region from each signal's current value, renders a single frame,
      * and returns the PNG bytes. The effect row is `< (Async & Scope & Abort[ThreeException])`.
      */
    def toImage(
        scene: Three,
        camera: Three.Ast.Camera,
        width: Int = 1280,
        height: Int = 720
    )(using Frame): kyo.internal.Image < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.toImage(scene, camera, width, height)

    /** Embeds `scene` as a first-class child of a kyo-ui tree: returns a `UI.Ast.BackendNode` whose
      * `<canvas>` kyo-ui lays out and renders on every runner, registry-dispatched to `ThreeBackend`
      * at mount. The 3D scene mounts at page mount and disposes at page teardown (client-side); the
      * renderer, reconciler, GL contexts, and pointer listeners bind to the page mount Scope and are
      * released at teardown. The frame loop runs as a fiber forked under the page Scope: no leaked GL
      * context, no orphaned frame loop. Shared `SignalRef`s bridge exactly as in the side-by-side path.
      *
      * Usage: `UI.div(controls, Three.embed(scene, camera), footer)`.
      */
    def embed(
        scene: Three,
        camera: Three.Ast.Camera,
        frames: ThreeFrames = ThreeFrames.Raf
    )(using Frame): UI.Ast.BackendNode =
        Three.Ast.Embed(scene, camera, frames)

end Three
