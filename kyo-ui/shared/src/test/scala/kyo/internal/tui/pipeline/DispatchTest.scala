package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class DispatchTest extends Test:

    import AllowUnsafe.embrace.danger

    val defaultStyle = FlatStyle.Default
    val viewport     = Rect(0, 0, 80, 24)

    def node(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        key: Maybe[WidgetKey] = Absent,
        id: Maybe[String] = Absent,
        forId: Maybe[String] = Absent,
        tabIndex: Maybe[Int] = Absent,
        disabled: Boolean = false,
        children: Chunk[Laid] = Chunk.empty
    ): Laid.Node =
        val base = Handlers.empty
            .withId(id)
            .withForId(forId)
            .withTabIndex(tabIndex)
            .withDisabled(disabled)
        val handlers = key match
            case Present(k) => base.withWidgetKey(k)
            case _          => base
        val bounds = Rect(x, y, w, h)
        Laid.Node(ElemTag.Div, defaultStyle, handlers, bounds, bounds, viewport, viewport, children)
    end node

    def mkKey(name: String): WidgetKey =
        WidgetKey(Frame.derive, Chunk(name))

    "hitTest" - {
        "deepest node returned" in {
            val inner  = node(5, 5, 10, 10)
            val outer  = node(0, 0, 80, 24, children = Chunk(inner))
            val layout = LayoutResult(outer, Chunk.empty)
            val result = Dispatch.hitTest(layout, 7, 7)
            // Should return inner, not outer
            result match
                case Present(n) => assert(n eq inner)
                case _          => fail("expected inner node")
        }

        "outside bounds returns Absent" in {
            val root   = node(0, 0, 10, 10)
            val layout = LayoutResult(root, Chunk.empty)
            assert(Dispatch.hitTest(layout, 15, 15).isEmpty)
        }

        "popup checked before base" in {
            val base   = node(0, 0, 80, 24, key = Maybe(mkKey("base")))
            val popup  = node(5, 5, 10, 10, key = Maybe(mkKey("popup")))
            val layout = LayoutResult(base, Chunk(popup))
            val result = Dispatch.hitTest(layout, 7, 7)
            result match
                case Present(n) => assert(n.handlers.widgetKey.exists(_.dynamicPath == Chunk("popup")))
                case _          => fail("expected popup node")
        }

        "point on boundary (x = x+w-1)" in {
            val root   = node(0, 0, 10, 10)
            val layout = LayoutResult(root, Chunk.empty)
            assert(Dispatch.hitTest(layout, 9, 9).nonEmpty)  // x+w-1 = 9, y+h-1 = 9
            assert(Dispatch.hitTest(layout, 10, 10).isEmpty) // at x+w, y+h — outside
        }
    }

    "findByKey" - {
        "found" in {
            val k    = mkKey("target")
            val leaf = node(0, 0, 10, 10, key = Maybe(k))
            val root = node(0, 0, 80, 24, children = Chunk(leaf))
            assert(Dispatch.findByKey(root, k).nonEmpty)
        }

        "not found" in {
            val root = node(0, 0, 80, 24)
            assert(Dispatch.findByKey(root, mkKey("nonexistent")).isEmpty)
        }

        "deeply nested" in {
            val k    = mkKey("deep")
            val leaf = node(0, 0, 5, 5, key = Maybe(k))
            val mid  = node(0, 0, 10, 10, children = Chunk(leaf))
            val root = node(0, 0, 80, 24, children = Chunk(mid))
            assert(Dispatch.findByKey(root, k).nonEmpty)
        }

        "Text node returns Absent" in {
            val text = Laid.Text("hello", defaultStyle, Rect(0, 0, 5, 1), viewport)
            assert(Dispatch.findByKey(text, mkKey("any")).isEmpty)
        }
    }

    "findByUserId" - {
        "found" in {
            val leaf = node(0, 0, 10, 10, id = Maybe("myInput"))
            val root = node(0, 0, 80, 24, children = Chunk(leaf))
            assert(Dispatch.findByUserId(root, "myInput").nonEmpty)
        }

        "not found" in {
            val root = node(0, 0, 80, 24)
            assert(Dispatch.findByUserId(root, "nope").isEmpty)
        }
    }

    "dispatch" - {
        "LeftRelease clears activeId" in run {
            val state  = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            val root   = node(0, 0, 80, 24)
            val layout = LayoutResult(root, Chunk.empty)
            state.prevLayout = Maybe(layout)
            state.activeId.set(Maybe(mkKey("something")))
            Dispatch.dispatch(InputEvent.Mouse(MouseKind.LeftRelease, 0, 0), layout, state).andThen {
                assert(state.activeId.get().isEmpty)
            }
        }

        "Move updates hoveredId" in run {
            val state  = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            val k      = mkKey("hoverable")
            val target = node(0, 0, 10, 10, key = Maybe(k))
            val layout = LayoutResult(target, Chunk.empty)
            Dispatch.dispatch(InputEvent.Mouse(MouseKind.Move, 5, 5), layout, state).andThen {
                assert(state.hoveredId.get() == Maybe(k))
            }
        }

        "Move outside clears hoveredId" in run {
            val state  = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            val target = node(0, 0, 10, 10, key = Maybe(mkKey("a")))
            val layout = LayoutResult(target, Chunk.empty)
            state.hoveredId.set(Maybe(mkKey("a")))
            Dispatch.dispatch(InputEvent.Mouse(MouseKind.Move, 50, 50), layout, state).andThen {
                assert(state.hoveredId.get().isEmpty)
            }
        }

        "disabled node does not fire onClick" in run {
            var fired        = false
            val k            = mkKey("btn")
            val target       = node(0, 0, 10, 10, key = Maybe(k), tabIndex = Maybe(0), disabled = true)
            val withClick    = target.handlers.withOnClick(Sync.Unsafe.defer { fired = true })
            val disabledNode = target.copy(handlers = withClick)
            val layout       = LayoutResult(disabledNode, Chunk.empty)
            val state        = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            state.focusedId.set(Maybe(k))
            Dispatch.dispatch(InputEvent.Mouse(MouseKind.LeftPress, 5, 5), layout, state).andThen {
                assert(!fired)
            }
        }
    }

    "cycleFocus" - {
        "empty focusableIds is no-op" in run {
            val state  = new ScreenState(ResolvedTheme.resolve(Theme.Default))
            val root   = node(0, 0, 80, 24)
            val layout = LayoutResult(root, Chunk.empty)
            // focusableIds is empty by default
            Dispatch.dispatch(InputEvent.Key(UI.Keyboard.Tab, false, false, false), layout, state).andThen {
                assert(state.focusedId.get().isEmpty)
            }
        }
    }

end DispatchTest
