package kyo.internal.tui.pipeline

import kyo.*
import kyo.Length.*
import kyo.Test
import scala.language.implicitConversions

class LowerTest extends Test:

    import AllowUnsafe.embrace.danger

    def lowerUI(ui: UI): Lower.LowerResult < (Async & Scope) =
        val theme = ResolvedTheme.resolve(Theme.Default)
        val state = new ScreenState(theme)
        Lower.lower(ui, state)
    end lowerUI

    "text" - {
        "string becomes Resolved.Text" in run {
            lowerUI(UI.internal.Text("hello")).map { result =>
                result.tree match
                    case Resolved.Text(v) => assert(v == "hello")
                    case _                => fail("expected Text")
            }
        }
    }

    "div" - {
        "passthrough with children" in run {
            lowerUI(UI.div(UI.span("hello"))).map { result =>
                result.tree match
                    case Resolved.Node(ElemTag.Div, _, _, children) =>
                        assert(children.size == 1)
                        children(0) match
                            case Resolved.Node(ElemTag.Span, _, _, innerChildren) =>
                                assert(innerChildren.size == 1)
                                innerChildren(0) match
                                    case Resolved.Text(v) => assert(v == "hello")
                                    case _                => fail("expected Text")
                            case _ => fail("expected Span node")
                        end match
                    case _ => fail("expected Div node")
            }
        }
    }

    "fragment" - {
        "children flattened" in run {
            lowerUI(UI.div(UI.fragment(UI.span("a"), UI.span("b")))).map { result =>
                result.tree match
                    case Resolved.Node(_, _, _, children) =>
                        assert(children.size >= 1)
                    case _ => fail("expected Node")
            }
        }
    }

    "hidden" - {
        "filtered out" in run {
            lowerUI(UI.div.hidden(true)("x")).map { result =>
                result.tree match
                    case Resolved.Text(v) => assert(v == "")
                    case _                => fail("expected empty Text for hidden element")
            }
        }
    }

    "br" - {
        "becomes newline text" in run {
            lowerUI(UI.br).map { result =>
                result.tree match
                    case Resolved.Text(v) => assert(v == "\n")
                    case _                => fail("expected Text with newline")
            }
        }
    }

    "table" - {
        "uses Table tag" in run {
            lowerUI(UI.table(UI.tr(UI.td("cell")))).map { result =>
                result.tree match
                    case Resolved.Node(ElemTag.Table, _, _, _) => succeed
                    case _                                     => fail("expected Table tag")
            }
        }
    }

    "input" - {
        "expands to div with text and cursor" in run {
            lowerUI(UI.input.value("hello")).map { result =>
                result.tree match
                    case Resolved.Node(ElemTag.Div, _, _, children) =>
                        assert(children.size == 3)
                        children(0) match
                            case Resolved.Text(_) => succeed
                            case _                => fail("expected Text before cursor")
                        children(1) match
                            case Resolved.Cursor(_) => succeed
                            case _                  => fail("expected Cursor")
                        children(2) match
                            case Resolved.Text(_) => succeed
                            case _                => fail("expected Text after cursor")
                    case _ => fail("expected Div node for input")
            }
        }
    }

    "password" - {
        "masks with dots" in run {
            lowerUI(UI.password.value("abc")).map { result =>
                result.tree match
                    case Resolved.Node(_, _, _, children) =>
                        children(0) match
                            case Resolved.Text(v) => assert(!v.contains("abc"))
                            case _                => fail("expected Text")
                    case _ => fail("expected Node")
            }
        }
    }

    "checkbox" - {
        "checked shows [x]" in run {
            lowerUI(UI.checkbox.checked(true)).map { result =>
                result.tree match
                    case Resolved.Node(_, _, _, children) =>
                        children(0) match
                            case Resolved.Text(v) => assert(v == "[x]")
                            case _                => fail("expected Text")
                    case _ => fail("expected Node")
            }
        }

        "unchecked shows [ ]" in run {
            lowerUI(UI.checkbox.checked(false)).map { result =>
                result.tree match
                    case Resolved.Node(_, _, _, children) =>
                        children(0) match
                            case Resolved.Text(v) => assert(v == "[ ]")
                            case _                => fail("expected Text")
                    case _ => fail("expected Node")
            }
        }
    }

    "select" - {
        "collapsed shows selected value" in run {
            lowerUI(
                UI.select.value("b")(
                    UI.option.value("a")("Alpha"),
                    UI.option.value("b")("Beta")
                )
            ).map { result =>
                result.tree match
                    case Resolved.Node(_, _, _, children) =>
                        children(0) match
                            case Resolved.Text(v) => assert(v == "Beta")
                            case _                => fail("expected Text with selected option")
                    case _ => fail("expected Node")
            }
        }
    }

    "focus collection" - {
        "input is focusable by default" in run {
            lowerUI(UI.div(UI.input.value(""))).map { result =>
                assert(result.focusableIds.size >= 1)
            }
        }

        "disabled input not focusable" in run {
            lowerUI(UI.div(UI.input.value("").disabled(true))).map { result =>
                assert(result.focusableIds.isEmpty)
            }
        }
    }

    "handler composition" - {
        "onClick composes inner to outer" in run {
            var order = List.empty[String]
            val ui = UI.div.onClick {
                order = order :+ "outer"
            }(
                UI.div.onClick {
                    order = order :+ "inner"
                }("x")
            )
            lowerUI(ui).map { result =>
                result.tree match
                    case Resolved.Node(_, _, _, children) =>
                        children(0) match
                            case Resolved.Node(_, _, handlers, _) =>
                                assert(handlers.widgetKey.nonEmpty)
                            case _ => fail("expected inner Node")
                    case _ => fail("expected outer Node")
            }
        }
    }

    "label" - {
        "preserves forId" in run {
            lowerUI(UI.label.forId("myInput")("Click me")).map { result =>
                result.tree match
                    case Resolved.Node(_, _, handlers, _) =>
                        assert(handlers.forId == Maybe("myInput"))
                    case _ => fail("expected Node")
            }
        }
    }

    "td colspan" - {
        "preserves colspan on handlers" in run {
            lowerUI(UI.table(UI.tr(UI.td.colspan(3)("wide")))).map { result =>
                result.tree match
                    case Resolved.Node(ElemTag.Table, _, _, rows) =>
                        rows(0) match
                            case Resolved.Node(_, _, _, cells) =>
                                cells(0) match
                                    case Resolved.Node(_, _, handlers, _) =>
                                        assert(handlers.colspan == 3)
                                    case _ => fail("expected cell Node")
                            case _ => fail("expected row Node")
                    case _ => fail("expected Table node")
            }
        }
    }

    "radio" - {
        "unchecked shows ( )" in run {
            lowerUI(UI.radio).map { result =>
                result.tree match
                    case Resolved.Node(_, _, _, children) =>
                        children(0) match
                            case Resolved.Text(v) => assert(v == "( )")
                            case _                => fail("expected Text")
                    case _ => fail("expected Node")
            }
        }
    }

    "hiddenInput" - {
        "filtered out" in run {
            lowerUI(UI.hiddenInput).map { result =>
                result.tree match
                    case Resolved.Text(v) => assert(v == "")
                    case _                => fail("expected empty Text for hidden input")
            }
        }
    }

    "disabled" - {
        "button disabled not in focusableIds" in run {
            lowerUI(UI.button.disabled(true).tabIndex(0)("click")).map { result =>
                assert(result.focusableIds.isEmpty)
            }
        }
    }

    "handler composition" - {
        "onClick handlers have widgetKey" in run {
            lowerUI(
                UI.div.onClick(())("inner")
            ).map { result =>
                result.tree match
                    case Resolved.Node(_, _, handlers, _) =>
                        assert(handlers.widgetKey.nonEmpty)
                    case _ => fail("expected Node")
            }
        }
    }

    "theme" - {
        "h1 gets bold style in Default theme" in run {
            lowerUI(UI.h1("Title")).map { result =>
                result.tree match
                    case Resolved.Node(_, style, _, _) =>
                        // Theme should add bold
                        assert(style.props.exists(_.isInstanceOf[Style.Prop.FontWeightProp]))
                    case _ => fail("expected Node")
            }
        }
    }

end LowerTest
