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

object Three:

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

    /** A subtree driven by a `Signal[Three]`: the bound region re-renders on each emission. */
    def reactive(signal: Signal[Three])(using Frame): Ast.Reactive =
        Ast.Reactive(signal)

    /** Shows `body` while `condition` holds, else `Three.empty`. */
    def when(condition: Signal[Boolean])(body: => Three)(using Frame): Ast.Reactive =
        Ast.Reactive(condition.map(c => if c then body else empty))

    /** The typed raw-three.js escape hatch: `build` produces the live object the reconciler inserts.
      * `In` is the user's own typed parameter object. A `Custom` is `Interactive` and `Animated`.
      */
    def custom[In](build: In => js.Dynamic)(input: In)(using Frame): Ast.Custom[In] =
        Ast.Custom(build, input, Ast.MeshProps(), Chunk.empty)

    // ---- Orbit camera controls -----------------------------------------------------

    /** An orbit-camera control node (design 02-design-r2 FORK-Y-A, DY-06): add `Three.controls(...)` to a
      * scene and the island binds a three.js `OrbitControls` instance to the live camera and canvas at
      * mount, disposed on the mount `Scope` close (no leaked listener). Drag orbits, scroll zooms, and
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
        /** Projects a value signal into a reactive subtree via `f`. */
        def render(f: A => Three)(using Frame): Ast.Reactive =
            Ast.Reactive(signal.map(f(_)))
    end extension

    extension [A](signal: Signal[Chunk[A]])
        /** One child node per element, reconciled by position. */
        def foreach(render: A => Three)(using Frame): Ast.Foreach[A] =
            Ast.Foreach(signal, Absent, (_, a) => render(a))

        /** Keyed reconciliation so reorders and insertions reuse the matching live nodes (the GPU
          * buffers survive), important for 3D where recreating a mesh re-uploads buffers.
          */
        def foreachKeyed(key: A => String)(render: A => Three)(using Frame): Ast.Foreach[A] =
            Ast.Foreach(signal, Present(key), (_, a) => render(a))
    end extension

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
        sealed trait Node extends Three:
            type Self <: Node
            def children: Chunk[Three]
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

        final case class Reactive(signal: Signal[Three])(using val frame: Frame) extends Node:
            type Self = Reactive
            def children: Chunk[Three] = Chunk.empty
        end Reactive

        final case class Foreach[A](
            signal: Signal[Chunk[A]],
            key: Maybe[A => String],
            render: (Int, A) => Three
        )(using val frame: Frame) extends Node:
            type Self = Foreach[A]
            def children: Chunk[Three] = Chunk.empty
        end Foreach

        // ---- Orbit controls node ----------------------------------------------------------

        /** The immutable AST value for orbit camera control (design 02-design-r2 DY-06, FORK-Y-A),
          * produced by [[Three.controls]]. The island binds a live three.js `OrbitControls` instance from
          * it at mount over the live camera and canvas, disposed on the mount `Scope` close. It renders no
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

    end Ast

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

    /** Embeds `scene` as a first-class child of a kyo-ui tree: returns a `UI.Ast.Host` whose
      * `<canvas>` kyo-ui lays out and renders on every runner, and into which the 3D scene mounts
      * at page mount and disposes at page teardown (client-side). The renderer, reconciler, GL
      * contexts, and pointer listeners bind to the page mount Scope and are released at teardown.
      * The frame loop runs as a fiber forked under the page Scope: no leaked GL context, no
      * orphaned frame loop. Shared `SignalRef`s bridge exactly as in the side-by-side path.
      *
      * Usage: `UI.div(controls, Three.embed(scene, camera), footer)`.
      */
    def embed(
        scene: Three,
        camera: Three.Ast.Camera,
        frames: ThreeFrames = ThreeFrames.Raf
    )(using Frame): UI.Ast.Host =
        ThreeMount.embed(scene, camera, frames)

    /** Option-Y server-feeds-by-signal-id surface (design 02-design-r2, Decisions D-001/D-002/D-004).
      *
      * Under Y the client owns and animates the real scene (its `onFrame`/`onClick` closures compiled
      * into the island bundle) while the SERVER feeds reactive DATA addressed by a string signal id over
      * the EXISTING kyo-ui WebSocket `HostUpdate` transport. The two halves agree only on the set of
      * string ids: a `Three.Feed.serverSignal[A](id, ...)` is server-owned (a clock fiber writes it) and
      * mirrored client-side under the SAME id; each server emission becomes a
      * `HostPayload.SignalUpdate(id, encoded)` wire leaf the client decodes and writes into the mirror
      * `SignalRef[A]`, which the scene's existing `.color`/`.position` bound setters already observe
      * through the `forkBoundRef`/`patchProp` path. The fed value crosses as a `Json.encode`d string of
      * the `Schema`-serialized `A` (the prove-the-mechanism resolution of Q-Y2-3), so any `A: Schema`
      * (the bound setters' `Color`/`Vec3`/`Double` and a plain `Int` alike) round-trips identically
      * client-side and server-side without any typed-value wire union.
      *
      * This object carries the minimal real mechanism exercised by the prove-the-mechanism demo: the
      * mirror-`SignalRef` factory, the server-side wire-leaf encoder, and the client-side per-id feed
      * receiver. The full `run`/`emit` serve wiring layers on top of this same seam.
      */
    object Feed:

        /** One registered server-owned fed signal: its protocol `id` and a closure that, given the
          * per-session host-update sink, runs the signal's `observe` loop (each emission a
          * `HostPayload.SignalUpdate(id, encoded)` pushed through the sink). The closure captures the
          * signal's own `A`/`Schema[A]`/`SignalRef[A]`, so the registry stays homogeneous and type-safe
          * with no existential leaking onto the wire path. `observe`'s first setup runs on the current
          * value, so a freshly connected client receives the signal's present value immediately.
          */
        final private[kyo] case class FeedEntry(
            id: String,
            observe: (kyo.internal.HostPayload => Unit < Async) => Unit < (Async & Scope)
        )

        /** One registered server-side app-event handler (design 02-design-r2, Decision D-003): its routing
          * `eventId` and a closure that, given the inbound `AppEvent`'s `Json.encode`d string, decodes it
          * with the handler's own `Schema[A]` and runs the user's handler. The closure captures `A`/
          * `Schema[A]`, so the registry stays homogeneous with no existential leaking; a decode failure is a
          * log-and-skip (the fire-and-forget back-channel policy), never a thrown frame.
          */
        final private[kyo] case class AppEventHandler(
            eventId: String,
            run: String => Unit < Async
        )

        /** The request-context fed-signal registry (design 02-design-r2, Decision D-004): a mutable
          * holder of the `FeedEntry`s the `serverSignal` calls record AND the `AppEventHandler`s the
          * `onAppEvent` calls record while the `ui` builder runs. `run` establishes one fresh registry per
          * WebSocket session (via [[registryLocal]] `let`), runs the builder inside it, reads the recorded
          * feed entries to fork the feed observers, and reads the recorded app-event handlers to route
          * inbound `AppEvent`s. The capability to feed and to handle is established once at `run`, never
          * threaded through `serverSignal`/`onAppEvent`'s signatures.
          */
        final private[kyo] class FeedRegistry(
            private val entries: AtomicRef[Chunk[FeedEntry]],
            private val handlers: AtomicRef[Chunk[AppEventHandler]]
        ):
            def register(entry: FeedEntry)(using Frame): Unit < Sync          = entries.updateAndGet(_.append(entry)).unit
            def registerHandler(h: AppEventHandler)(using Frame): Unit < Sync = handlers.updateAndGet(_.append(h)).unit
            def all(using Frame): Chunk[FeedEntry] < Sync                     = entries.get
            def allHandlers(using Frame): Chunk[AppEventHandler] < Sync       = handlers.get
        end FeedRegistry

        private[kyo] object FeedRegistry:
            def init(using Frame): FeedRegistry < Sync =
                for
                    entries  <- AtomicRef.init(Chunk.empty[FeedEntry])
                    handlers <- AtomicRef.init(Chunk.empty[AppEventHandler])
                yield FeedRegistry(entries, handlers)
        end FeedRegistry

        // Absent outside a `run` WebSocket session (a plain client island calling `serverSignal` to get
        // the mirror, or a unit test): registration is then a no-op and `serverSignal` yields just the
        // ref. `run` sets it via `let` so the builder's `serverSignal` calls populate that session's
        // registry without any session value crossing the signature.
        private[kyo] val registryLocal: Local[Maybe[FeedRegistry]] = Local.init(Absent)

        /** Allocates the server-owned (or, on the island, the client mirror) `SignalRef[A]` addressed by
          * `id`. On the SERVER the app writes it and the feed runner emits each change by id over the WS;
          * on the CLIENT the island binds the returned ref into the scene with the existing setters and
          * [[connect]] writes inbound feeds into it. The `id` is the protocol key the two halves agree on;
          * `A: Schema` is what lets the value cross the wire.
          *
          * When called inside a [[run]] WebSocket session (the server path), the call also registers an
          * observer of the returned ref in the session's fed-signal registry, so `run` feeds each emission
          * by `id` over the WS. Outside a session (the client mirror, a unit test) the registration is a
          * no-op and the bare ref is returned.
          */
        def serverSignal[A: Schema](id: String, initial: A)(using Frame, CanEqual[A, A]): SignalRef[A] < Sync =
            for
                ref <- Signal.initRef[A](initial)
                _ <- registryLocal.use {
                    case Present(reg) =>
                        reg.register(FeedEntry(id, sink => ref.observe(value => sink(encodeUpdate(id, value)))))
                    case Absent => Kyo.unit
                }
            yield ref

        /** Structural fed-signal overload (design 02-design-r2, Decision D-002, DY-03): a server-fed
          * `Chunk[A]` the client's own `foreachKeyed` reconciler diffs locally.
          *
          * Allocates the server-owned (or, on the island, the client mirror) `SignalRef[Chunk[A]]`
          * addressed by `id`. On the SERVER, when called inside a [[run]] WebSocket session, it registers
          * an observer of the returned ref in the session's fed-signal registry so each emission becomes a
          * `HostPayload.SignalChunk(id, encoded)` over the WS, the whole collection snapshot encoded as the
          * `Json.encode`d string of the `Schema`-serialized `Chunk[A]`. On the CLIENT the island binds the
          * returned ref with the EXISTING `.foreachKeyed(key)(render)` extension and calls
          * [[connectChunk]]; an inbound `SignalChunk` for this `id` decodes with the same `Schema[A]` and
          * writes the snapshot into the mirror, and the client's own keyed reconciler
          * (`subscribeReactiveRegions`) diffs/splices it locally (an unchanged key reuses its live object,
          * the GPU buffers survive). The wire carries the typed element DATA, never a flattened rendered
          * subtree: the server feeds the snapshot and the diff runs client-side.
          *
          * Distinct from the single-value [[serverSignal]]: it threads a `Chunk[A]` value type the
          * single-value form cannot express and registers the id as a STRUCTURAL feed (the client routes it
          * to its keyed reconciler, not a prop mirror). Same id-registration core, distinct typed surface.
          */
        def serverSignal[A: Schema](id: String, initial: Chunk[A])(using
            Frame,
            CanEqual[A, A]
        ): SignalRef[Chunk[A]] < Sync =
            for
                ref <- Signal.initRef[Chunk[A]](initial)
                _ <- registryLocal.use {
                    case Present(reg) =>
                        reg.register(FeedEntry(id, sink => ref.observe(value => sink(encodeChunkUpdate(id, value)))))
                    case Absent => Kyo.unit
                }
            yield ref

        /** Encodes a server-owned fed value as the `HostPayload.SignalUpdate(id, encoded)` wire leaf the
          * feed runner emits over the existing `HtmlOp.HostUpdate` transport (`UIServer.emitHostUpdate`).
          * The value crosses as the `Json.encode`d string of its `Schema`, decoded client-side by
          * [[connect]] with the same `Schema[A]`.
          */
        private[kyo] def encodeUpdate[A: Schema](id: String, value: A)(using Frame): kyo.internal.HostPayload =
            kyo.internal.HostPayload.SignalUpdate(id, Json.encode[A](value))

        /** Encodes a server-owned fed `Chunk[A]` snapshot as the `HostPayload.SignalChunk(id, encoded)`
          * wire leaf the structural feed runner emits (design 02-design-r2 D-002, DY-03). The whole
          * collection crosses as the `Json.encode`d string of its `Schema`, decoded client-side by
          * [[connectChunk]] with the same `Schema[A]`; the client's own keyed reconciler then diffs it.
          */
        private[kyo] def encodeChunkUpdate[A: Schema](id: String, value: Chunk[A])(using Frame): kyo.internal.HostPayload =
            kyo.internal.HostPayload.SignalChunk(id, Json.encode[Chunk[A]](value))

        /** Wires the client mirror (the island-side feed connect): registers a receiver on
          * `window.__kyoHostChannels[id]` (the same registry the inline kyo-ui clientJs routes a
          * `HostUpdate` into, `HtmlRenderer.scala:771-799`) that decodes an inbound
          * `HostPayload.SignalUpdate` for this `id` with `Schema[A]` and writes the decoded value into
          * `mirror`. The existing `forkBoundRef`/`patchProp` fiber the scene mount already forked for
          * `mirror` then patches exactly the one bound live node. A malformed or wrong-id payload is a
          * silent no-op. The receiver is dropped on `Scope` close.
          *
          * This is the per-app island entry's feed-connect helper (design 02-design-r2 open question
          * Q-Y2-1): the island's `@JSExportTopLevel` main mounts the scene via `Three.runMount` and calls
          * this once per fed signal id under the mount Scope. It is exercised by the prove-the-mechanism
          * demo; the public surface here is the minimal island connect the per-app entry needs.
          */
        def connect[A: Schema](id: String, mirror: SignalRef[A])(using
            Frame
        ): Unit < (Async & Scope) =
            ThreeMount.connectFeed[A](id, mirror)

        /** Wires the client mirror for a STRUCTURAL feed (design 02-design-r2 D-002, DY-03): the island-side
          * connect for a `Three.Feed.serverSignal[Chunk[A]]`. Registers a receiver on
          * `window.__kyoHostChannels[id]` that decodes an inbound `HostPayload.SignalChunk` for this `id`
          * with `Schema[Chunk[A]]` and writes the whole decoded snapshot into `mirror`. The scene bound the
          * mirror with `.foreachKeyed(key)(render)`, so the write drives the client's OWN keyed reconciler
          * (`subscribeReactiveRegions`), which diffs/splices the snapshot locally: an unchanged key reuses
          * its live object (GPU buffers survive), a new key materializes, a dropped key disposes. A
          * malformed or wrong-id payload is a silent no-op. The receiver is dropped on `Scope` close.
          *
          * The per-app island entry calls this once per fed structural signal id under the mount Scope, the
          * structural analog of [[connect]].
          */
        def connectChunk[A: Schema](id: String, mirror: SignalRef[Chunk[A]])(using
            Frame
        ): Unit < (Async & Scope) =
            ThreeMount.connectFeedChunk[A](id, mirror)

        /** The Option-Y client->server typed app-event back-channel (design 02-design-r2, Decision D-003,
          * DY-04). Called from inside a CLIENT `onClick` (or other handler) on the user's live scene: the
          * client raycasts and runs `onClick` LOCALLY, and within that closure `emit` posts a typed
          * `[A: Schema]` app event addressed by `id` over the SAME WebSocket. The server's [[run]] routes it
          * to the handler registered for that `id` via [[onAppEvent]], which reflects it into a server-owned
          * fed signal it feeds back, closing the hook-and-feed loop.
          *
          * The event crosses as the `Json.encode`d string of its `Schema` inside a `private[kyo]`
          * `UIEvent.AppEvent(path, id, encoded)` (the user sees only this typed surface, never the wire
          * envelope). When no feed channel is bound (called outside an island feed context, e.g. before the
          * WS is open or in a non-island context), the post fails with `Abort[ThreeException.FeedUnavailable]`
          * visible in the row, never a silent drop. An event for an `id` with no registered server handler
          * is a log-and-skip server-side (the fire-and-forget back-channel policy).
          */
        def emit[A: Schema](id: String, event: A)(using Frame): Unit < (Async & Abort[ThreeException]) =
            ThreeMount.postAppEvent[A](id, event)

        /** Registers a server-side handler for the typed app event `id` (design 02-design-r2, Decision
          * D-003, DY-04): the server leg of the back-channel that [[emit]] drives from the client. Called
          * inside the [[run]] `ui` builder, exactly as [[serverSignal]] is, so the handler records itself in
          * the session's request-context registry; [[run]] then routes each inbound `AppEvent` for `id` to
          * this handler. The handler receives the decoded typed event and typically reflects it into a
          * server-owned fed signal it also declared with `serverSignal`, feeding the result back to the
          * client. Outside a [[run]] session (a unit test, a non-serve context) the registration is a no-op.
          *
          * Like [[serverSignal]] the capability is established once at `run`; the handler is not threaded
          * through a signature. A decode failure or an event for an unregistered id is a server-side
          * log-and-skip, never a thrown frame (the fire-and-forget policy).
          */
        def onAppEvent[A: Schema](id: String)(handler: A => Unit < Async)(using Frame): Unit < Sync =
            registryLocal.use {
                case Present(reg) =>
                    reg.registerHandler(AppEventHandler(
                        id,
                        encoded =>
                            Json.decode[A](encoded) match
                                case Result.Success(event) => handler(event)
                                case Result.Failure(_)     => Log.warn(s"Three.Feed app event '$id' decode failed; dropped")
                                case Result.Panic(ex)      => Log.error(s"Three.Feed app event '$id' decode panicked", ex)
                    ))
                case Absent => Kyo.unit
            }

        /** The Option-Y serve entry (design 02-design-r2, Decision D-004, DY-05): returns the HTTP
          * handlers (an SSR page GET and a WebSocket route) that serve a feed-by-signal-id app. Compose
          * the returned handlers with any static handlers (the client island bundle, three.js) via
          * `HttpServer.init`.
          *
          * The page is built from `head`: it links the client island bundle through `head.moduleScript`
          * (the per-app `@JSExportTopLevel` entry that mounts the real scene via `Three.runMount` and
          * connects each fed signal id via [[connect]], design Decision D-001) and carries the inline
          * kyo-ui client that routes each inbound `HostUpdate` into `window.__kyoHostChannels[id]`. The
          * `ui` builder renders the page body (a host `<canvas>` the island selects, plus any kyo-ui HUD).
          *
          * Per WebSocket connection the runner runs the `ui` builder once inside a fresh fed-signal
          * registry, so the `Three.Feed.serverSignal` calls in the builder record their ids, then forks
          * ONE observer per registered id, each signal emission becoming a
          * `HostUpdate(Seq(id), HostPayload.SignalUpdate(id, encoded))` over that WS (via
          * `UIServer.emitHostUpdate`). The observers and the kyo-ui reactive session bind to the
          * connection Scope and are interrupted on disconnect (no leaked fiber). The server never builds
          * the 3D scene graph; it learns only the fed ids (pure Y, NG1).
          *
          * Construction is `< Sync` (the per-connection observers fork at WebSocket-connect time under
          * the connection Scope, matching the `UI.runHandlers` substrate).
          */
        def run(basePath: String, head: UI.PageHead)(ui: => UI < Async)(using
            Frame
        ): Seq[HttpHandler[?, ?, ?]] < Sync =
            Sync.defer {
                val base = kyo.internal.UIServer.normalizePath(basePath)
                Seq(
                    kyo.internal.UIServer.pageHandler(basePath, head)(Sync.defer(ui)),
                    feedWsRoute(base, Sync.defer(ui))
                )
            }

        /** The feed WebSocket route: per connection, establishes a fresh [[FeedRegistry]] for the lifetime
          * of the `ui` builder run (so the builder's `serverSignal` and `onAppEvent` calls record their ids
          * and handlers), runs the kyo-ui reactive session for the same tree (any HUD reactivity), forks one
          * feed observer per registered id under the session Scope, and routes each inbound `AppEvent` by its
          * `eventId` to the matching registered handler. Each observer pushes the signal's value as a
          * `HostUpdate` whenever it emits; an inbound app event runs its handler (which typically sets a fed
          * signal, feeding the result back over the same WS). All fibers tear down on disconnect.
          */
        private def feedWsRoute(base: String, ui: => UI < Async)(using Frame): HttpHandler[Any, Any, Nothing] =
            HttpHandler.webSocket(s"$base/_kyo/ws") { (_, ws) =>
                FeedRegistry.init.map { registry =>
                    registryLocal.let(Present(registry)) {
                        kyo.internal.UIServer.serveSession(ws, ui) { _ =>
                            registry.all.map { entries =>
                                Kyo.foreachDiscard(entries) { entry =>
                                    // Fork each observer under the session Scope: Fiber.init registers an
                                    // interrupt on Scope close, so a disconnect tears every feed fiber down.
                                    Fiber.init(entry.observe(payload =>
                                        kyo.internal.UIServer.emitHostUpdate(ws, Seq(entry.id), payload)
                                    )).unit
                                }
                            }
                        } { (eventId, encoded) =>
                            // Route an inbound AppEvent to its registered handler by eventId. The registry is
                            // fully populated by the single `ui` run before the WS message loop starts, so the
                            // lookup is complete. An event for an unregistered id is a log-and-skip (the
                            // fire-and-forget back-channel policy), never a thrown frame.
                            registry.allHandlers.map { handlers =>
                                Maybe.fromOption(handlers.find(_.eventId == eventId)) match
                                    case Present(h) => h.run(encoded)
                                    case Absent     => Log.warn(s"Three.Feed app event for unregistered id '$eventId' dropped")
                            }
                        }
                    }
                }
            }

    end Feed

end Three
