package kyo.internal

import kyo.*
import kyo.UI.*

class ReactiveUIRootTest extends kyo.test.Test[Any]:

    private def clickCount(tree: (Unit < Async) => UI, path: Seq[String])(using Frame): Int < Async =
        for
            clicks <- AtomicInt.init(0)
            root   <- ReactiveUI.normalize(tree(clicks.incrementAndGet.unit), Seq.empty)
            _      <- root.handle(path, UIEvent.Click(path, MouseEventData(UI.Modifiers.none, Absent)))
            count  <- clicks.get
        yield count

    "element root dispatches to its child" in {
        clickCount(action => UI.div(UI.button("click").onClick(action)), Seq("0"))
            .map(count => assert(count == 1))
    }

    "reactive fragment root dispatches to its child" in {
        clickCount(action => Signal.initConst(UI.fragment(UI.button("click").onClick(action))), Seq("0"))
            .map(count => assert(count == 1))
    }

    "static fragment root dispatches to its child" in {
        clickCount(action => UI.fragment(UI.button("click").onClick(action)), Seq("0"))
            .map(count => assert(count == 1))
    }

    "nested static fragment root preserves child paths" in {
        clickCount(action => UI.fragment(UI.fragment(UI.button("click").onClick(action))), Seq("0", "0"))
            .map(count => assert(count == 1))
    }

    "keyed root dispatches at its rendered path" in {
        clickCount(action => UI.Ast.KeyedChild("key", UI.button("click").onClick(action)), Seq.empty)
            .map(count => assert(count == 1))
    }

    "element with a keyed child dispatches at the child path" in {
        clickCount(action => UI.Ast.Div(children = Chunk(UI.Ast.KeyedChild("key", UI.button("click").onClick(action)))), Seq("0"))
            .map(count => assert(count == 1))
    }

    "static fragment root discovers reactive and bound children" in {
        for
            text  <- Signal.initRef("initial")
            value <- Signal.initRef("value")
            root  <- ReactiveUI.normalize(UI.fragment(text.render(UI.span(_)), UI.textarea.value(value)), Seq.empty)
        yield assert(root.children.map(_.path) == Seq(Seq("0"), Seq("1")))
    }

    "keyed root discovers its bound child without adding a path segment" in {
        for
            value <- Signal.initRef("value")
            root  <- ReactiveUI.normalize(UI.Ast.KeyedChild("key", UI.textarea.value(value)), Seq.empty)
        yield assert(root.children.map(_.path) == Seq(Seq.empty))
    }

    "empty fragment root has no handlers or subscriptions" in {
        for
            root <- ReactiveUI.normalize(UI.fragment(), Seq.empty)
            keep <- root.handle(Seq.empty, UIEvent.Click(Seq.empty, MouseEventData(UI.Modifiers.none, Absent)))
        yield
            assert(root.children == Seq.empty)
            assert(keep)
    }

    "fragment root routes a keyed child by its rendered key" in {
        clickCount(action => UI.fragment(UI.Ast.KeyedChild("named", UI.button("click").onClick(action))), Seq("named"))
            .map(count => assert(count == 1))
    }

    private def renderedUpdates(tree: Signal[String] => UI)(using Frame): ((Seq[String], String), (Seq[String], String)) < (Async & Scope) =
        for
            value       <- Signal.initRef("initial")
            initial     <- Promise.init[(Seq[String], String), Any]
            replacement <- Promise.init[(Seq[String], String), Any]
            exchange = new UIExchange:
                def onChange(
                    region: ReactiveRegion,
                    path: Seq[String],
                    context: ReactiveRegion.RegionIdentity,
                    parentContext: ReactiveRegion.ParentContext,
                    previous: Maybe[UI],
                    ui: UI
                )(using Frame): Unit < Async =
                    HtmlRenderer.render(ui, path).map { html =>
                        val completion = if previous.isEmpty then initial else replacement
                        completion.completeDiscard(Result.Success((path, html)))
                    }
            root   <- ReactiveUI.normalize(tree(value), Seq.empty)
            _      <- ReactiveUI.subscribe(root, exchange)
            before <- initial.get
            _      <- value.set("updated")
            after  <- replacement.get
        yield (before, after)

    "fragment root subscribes to its child's signal updates" in {
        renderedUpdates(value => UI.fragment(value.render(UI.span(_)))).map { (before, after) =>
            assert(before == (Seq("0"), "<span data-kyo-path=\"0\">initial</span>"))
            assert(after == (Seq("0"), "<span data-kyo-path=\"0\">updated</span>"))
        }
    }

    "keyed root subscribes without introducing a path segment" in {
        renderedUpdates(value => UI.Ast.KeyedChild("named", value.render(UI.span(_)))).map { (before, after) =>
            assert(before == (Seq.empty, "<span data-kyo-path=\"\">initial</span>"))
            assert(after == (Seq.empty, "<span data-kyo-path=\"\">updated</span>"))
        }
    }

end ReactiveUIRootTest
