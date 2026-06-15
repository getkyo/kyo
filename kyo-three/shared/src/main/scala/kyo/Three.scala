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

end Three
