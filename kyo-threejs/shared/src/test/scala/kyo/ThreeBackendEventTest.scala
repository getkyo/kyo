package kyo

import kyo.Three.foreach
import kyo.Three.foreachKeyed
import kyo.Three.render
import kyo.internal.PointerKind
import kyo.internal.PointerWire

/** Tests for the server-side pointer resolution walk (`resolvePointer`, private on `object Three`)
  * and its `dispatchBackendEvent` entry point (`Three.Ast.Node`). Every leaf drives
  * `dispatchBackendEvent` directly on a pure AST value and observes the resolved handler's side effect
  * through a `Channel` latch; deterministic, no sleep, no live scene or GL.
  *
  * The walk is shared by all three pointer interactions: the event's own [[PointerKind]] picks the
  * handler at the addressed node, so a click and a hover reaching the same object run different closures.
  * The leaves below address `onClick` because it exercises every arm of the walk; the kind dispatch itself
  * is pinned by its own leaf at the end.
  */
class ThreeBackendEventTest extends ThreeTest:

    private def testPointer: Three.Pointer =
        Three.Pointer(
            point = Three.Vec3(0, 0, 0),
            distance = 1.0,
            ndc = (0.0, 0.0),
            buttons = Three.Pointer.Buttons.none
        )

    "foreach (positional): onClick resolves the item at the addressed INDEX, re-read fresh from signal.current on every dispatch (the SAME relPath resolves to whatever item currently sits there after a splice)" in {
        for
            items   <- Signal.initRef(Chunk("a", "b"))
            channel <- Channel.init[String](8)
            foreachNode = items.foreach { k =>
                Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Abort.runPartial[Closed](channel.put(k)).unit)
            }
            scene   = Three.scene(foreachNode)
            encoded = PointerWire.encode(PointerKind.Click, testPointer)
            _    <- scene.dispatchBackendEvent(Seq("0", "0"), encoded) // index 0 -> "a"
            got1 <- channel.take
            _    <- items.set(Chunk("c", "b"))                         // splice: index 0 is now "c"
            _    <- scene.dispatchBackendEvent(Seq("0", "0"), encoded) // SAME relPath, freshly resolved
            got2 <- channel.take
        yield
            assert(got1 == "a")
            assert(got2 == "c")
    }

    "foreach (keyed): onClick resolves the keyed child's handler by its key-addressed relPath" in {
        for
            items   <- Signal.initRef(Chunk("a", "b"))
            channel <- Channel.init[String](8)
            foreachNode = items.foreachKeyed(identity) { k =>
                Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Abort.runPartial[Closed](channel.put(k)).unit)
            }
            scene   = Three.scene(foreachNode)
            encoded = PointerWire.encode(PointerKind.Click, testPointer)
            _   <- scene.dispatchBackendEvent(Seq("0", "b"), encoded)
            got <- channel.take
        yield assert(got == "b")
    }

    "foreach: a relPath addressing a key with no onClick handler is a no-op" in {
        for
            items   <- Signal.initRef(Chunk("a"))
            channel <- Channel.init[String](8)
            foreachNode = items.foreachKeyed(identity)(_ => Three.mesh(Three.Geometry.box(), Three.Material.basic())) // no onClick
            sentinel = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                .onClick(_ => Abort.runPartial[Closed](channel.put("sentinel")).unit)
            scene   = Three.scene(foreachNode, sentinel)
            encoded = PointerWire.encode(PointerKind.Click, testPointer)
            _   <- scene.dispatchBackendEvent(Seq("0", "a"), encoded) // no onClick: no-op, nothing queued
            _   <- scene.dispatchBackendEvent(Seq("1"), encoded)      // sentinel dispatch, right after
            got <- channel.take
        yield assert(got == "sentinel")
    }

    "render/when: onClick resolves the CURRENT content at the boundary's OWN relPath (no extra segment), re-read fresh after a re-render" in {
        for
            cond    <- Signal.initRef(true)
            channel <- Channel.init[String](8)
            reactiveNode = cond.render { c =>
                if c then
                    Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Abort.runPartial[Closed](channel.put("A")).unit)
                else Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Abort.runPartial[Closed](channel.put("B")).unit)
            }
            scene   = Three.scene(reactiveNode)
            encoded = PointerWire.encode(PointerKind.Click, testPointer)
            _    <- scene.dispatchBackendEvent(Seq("0"), encoded) // the boundary's OWN path, no "reactive" segment
            got1 <- channel.take
            _    <- cond.set(false)
            _    <- scene.dispatchBackendEvent(Seq("0"), encoded) // SAME relPath, freshly resolved content
            got2 <- channel.take
        yield
            assert(got1 == "A")
            assert(got2 == "B")
    }

    "nested descendant: onClick resolves an Interactive nested below a foreach-keyed element, addressed by holderPath :+ key :+ childIndex" in {
        for
            items   <- Signal.initRef(Chunk("x"))
            channel <- Channel.init[String](8)
            foreachNode = items.foreachKeyed(identity) { k =>
                Three.group(
                    Three.mesh(Three.Geometry.box(), Three.Material.basic())
                        .onClick(_ => Abort.runPartial[Closed](channel.put(k)).unit)
                )
            }
            scene   = Three.scene(foreachNode)
            encoded = PointerWire.encode(PointerKind.Click, testPointer)
            _   <- scene.dispatchBackendEvent(Seq("0", "x", "0"), encoded)
            got <- channel.take
        yield assert(got == "x")
    }

    "Embed hop: a relPath starting with \"0\" maps to the embedded scene; \"1\" maps to the (non-clickable) camera and \"2\" has no child, both inert" in {
        for
            channel <- Channel.init[String](8)
            mesh = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                .onClick(_ => Abort.runPartial[Closed](channel.put("hit")).unit)
            scene   = Three.scene(mesh)
            camera  = Three.Camera.perspective()
            embed   = Three.Ast.Embed(scene, camera, ThreeFrames.Raf)
            encoded = PointerWire.encode(PointerKind.Click, testPointer)
            _    <- embed.dispatchBackendEvent(Seq("0", "0"), encoded) // "0" -> scene, then "0" indexes the mesh
            got1 <- channel.take
            _    <- embed.dispatchBackendEvent(Seq("1", "0"), encoded) // "1" -> camera (no onClick): inert
            _    <- embed.dispatchBackendEvent(Seq("2", "0"), encoded) // no such child index on an Embed: inert
            _    <- embed.dispatchBackendEvent(Seq("0", "0"), encoded) // sentinel, right after
            got2 <- channel.take
        yield
            assert(got1 == "hit")
            assert(got2 == "hit")
    }

    "a malformed encoded payload is a silent no-op: the dispatch completes with no observable side effect" in {
        for
            channel <- Channel.init[String](8)
            mesh = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                .onClick(_ => Abort.runPartial[Closed](channel.put("hit")).unit)
            scene = Three.scene(mesh)
            _ <- scene.dispatchBackendEvent(Seq("0"), "not valid json")                                   // malformed: dropped
            _ <- scene.dispatchBackendEvent(Seq("0"), PointerWire.encode(PointerKind.Click, testPointer)) // sentinel: the session survived
            got <- channel.take
        yield assert(got == "hit")
    }

    "the wire carries the event KIND, so the addressed node runs the handler the event names and not another" in {
        // Without a kind on the wire a hover and a click arriving at the same object are indistinguishable,
        // and the session can only ever run one of them. This is what makes the round trip kind-preserving:
        // encode as Over and the walk resolves onPointerOver, not the onClick sitting right beside it.
        for
            fired <- AtomicRef.init(Chunk.empty[String])
            mesh = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                .onClick(_ => fired.updateAndGet(_.appended("click")))
                .onPointerOver(_ => fired.updateAndGet(_.appended("over")))
                .onPointerOut(_ => fired.updateAndGet(_.appended("out")))
            scene = Three.scene(mesh)
            dispatch = (kind: PointerKind) =>
                scene.dispatchBackendEvent(Seq("0"), PointerWire.encode(kind, testPointer))
            _    <- dispatch(PointerKind.Over)
            _    <- dispatch(PointerKind.Click)
            _    <- dispatch(PointerKind.Out)
            seen <- fired.get
        yield assert(seen == Chunk("over", "click", "out"))
    }

end ThreeBackendEventTest
