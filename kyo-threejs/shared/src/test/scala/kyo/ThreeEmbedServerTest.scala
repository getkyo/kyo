package kyo

import kyo.internal.HtmlRenderer

/** Server-side proof that `Three.embed` constructs and SSRs on EVERY platform, the JVM included.
  *
  * A JVM (or Native) server builds a `Three.embed` tree, SSRs its `<canvas>` placeholder, and drives the
  * scene by server push; the browser client (the js/wasm island) renders the WebGL. WebGL never leaves
  * the client, so the whole server-constructible path, the AST, the SSR placeholder, and the
  * bound-prop/inbound-event walk, is cross-platform. This suite runs that path with no browser and no GL,
  * so it exercises exactly what a JVM server does. (The inbound half, a client pick resolving a
  * server-side handler, is covered on the same platforms by ThreeBackendEventTest.)
  */
class ThreeEmbedServerTest extends ThreeTest:

    private def scene(using Frame): Three.Ast.Scene =
        Three.scene(
            Three.mesh(Three.Geometry.box(), Three.Material.standard(color = Three.Color.red))
        )

    private def camera(using Frame): Three.Ast.Camera = Three.Camera.perspective()

    "Three.embed constructs a kyo-ui backend node, on every platform including the JVM server" in {
        val embed    = Three.embed(scene, camera)
        val asUi: UI = embed
        assert(asUi.isInstanceOf[UI.Ast.BackendNode])
        // The addressable backend children are (scene, camera): the reactive walk descends both, which is
        // what carries a server-driven bound prop or reactive region to the client.
        assert(embed.isInstanceOf[Three.Embedded])
    }

    "the server SSRs a Three.embed page as a <canvas data-kyo-backend=\"three\"> placeholder" in {
        val page = UI.div(Three.embed(scene, camera).id("stage"))
        HtmlRenderer.render(page, Seq.empty).map { html =>
            assert(html.contains("<canvas"), s"SSR must emit the canvas placeholder; got:\n$html")
            assert(
                html.contains("data-kyo-backend=\"three\""),
                s"the placeholder must carry the three backend tag so the client island mounts on it; got:\n$html"
            )
            // The placeholder is a LEAF: the 3D subtree is client-owned, so no GL is SSR'd on the server.
            assert(!html.contains("WebGLRenderer"), "the server must not render any WebGL into the page")
        }
    }

    "Three.embed's mounted handle is Absent before mount, and the SAME ref survives an .id() copy" in {
        val e      = Three.embed(scene, camera)
        val idCopy = e.id("stage")
        for
            before    <- e.mounted.current
            afterCopy <- idCopy.mounted.current
        yield
            assert(before == Absent, s"mounted must be Absent before the embed's canvas ever mounts, got $before")
            assert(afterCopy == Absent, s"the copied embed's mounted must still be Absent (no mount ran), got $afterCopy")
            assert(
                e.mounted eq idCopy.mounted,
                "id(v)'s copy(attrs = ...) must carry the SAME mountedRef through, never re-initialize it"
            )
        end for
    }

    "Three.embed defaults frames to ThreeFrames.Raf and accepts an explicit ThreeFrames" in {
        val a: UI.Ast.BackendNode = Three.embed(scene, camera)
        val b: UI.Ast.BackendNode = Three.embed(scene, camera, ThreeFrames.Clock(16.millis))
        a match
            case e: Three.Ast.Embed => assert(e.frames == ThreeFrames.Raf, s"the default frames must be ThreeFrames.Raf, got ${e.frames}")
            case other              => fail(s"expected a Three.Ast.Embed, got $other")
        b match
            case e: Three.Ast.Embed =>
                assert(e.frames == ThreeFrames.Clock(16.millis), s"the explicit frames must round-trip, got ${e.frames}")
            case other => fail(s"expected a Three.Ast.Embed, got $other")
        end match
    }

    "a server-driven bound prop leaves the embed SSR-able (the DATA is server-owned, the GL is not)" in {
        // A reactive-colour mesh: the colour signal is server-owned and reaches the client over the bound
        // prop path, while the canvas placeholder still SSRs the same way. Building and SSRing this on the
        // JVM is exactly the server half of a server-driven scene.
        Signal.initRef(Three.Color.red).map { colour =>
            val reactive = Three.scene(
                Three.mesh(Three.Geometry.box(), Three.Material.standard().color(colour))
            )
            val page = UI.div(Three.embed(reactive, camera).id("stage"))
            HtmlRenderer.render(page, Seq.empty).map { html =>
                assert(html.contains("data-kyo-backend=\"three\""))
            }
        }
    }

end ThreeEmbedServerTest
