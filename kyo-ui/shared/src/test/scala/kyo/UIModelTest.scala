package kyo

import UI.*
import UI.Ast.*
import scala.language.implicitConversions

class UIModelTest extends Test:

    "Type hierarchy" - {
        "Block elements" in {
            assert(Div().isInstanceOf[Block])
            assert(P().isInstanceOf[Block])
            assert(Section().isInstanceOf[Block])
            assert(Main().isInstanceOf[Block])
            assert(Header().isInstanceOf[Block])
            assert(Footer().isInstanceOf[Block])
            assert(Form().isInstanceOf[Block])
            assert(Pre().isInstanceOf[Block])
            assert(Code().isInstanceOf[Block])
            assert(Ul().isInstanceOf[Block])
            assert(Ol().isInstanceOf[Block])
            assert(Table().isInstanceOf[Block])
            assert(H1().isInstanceOf[Block])
            assert(H2().isInstanceOf[Block])
            assert(H3().isInstanceOf[Block])
            assert(H4().isInstanceOf[Block])
            assert(H5().isInstanceOf[Block])
            assert(H6().isInstanceOf[Block])
            assert(Hr().isInstanceOf[Block])
            assert(Br().isInstanceOf[Block])
            assert(Textarea().isInstanceOf[Block])
            assert(Select().isInstanceOf[Block])
            assert(Td().isInstanceOf[Block])
            assert(Th().isInstanceOf[Block])
            assert(Label().isInstanceOf[Block])
            assert(Opt().isInstanceOf[Block])
        }

        "Inline elements" in {
            assert(SpanElement().isInstanceOf[Inline])
            assert(Nav().isInstanceOf[Inline])
            assert(Li().isInstanceOf[Inline])
            assert(Tr().isInstanceOf[Inline])
            assert(Button().isInstanceOf[Inline])
            assert(Input().isInstanceOf[Inline])
            assert(Anchor().isInstanceOf[Inline])
            assert(Img().isInstanceOf[Inline])
        }

        "Focusable elements" in {
            assert(Button().isInstanceOf[Focusable])
            assert(Input().isInstanceOf[Focusable])
            assert(Textarea().isInstanceOf[Focusable])
            assert(Select().isInstanceOf[Focusable])
            assert(Anchor().isInstanceOf[Focusable])
            assert(!Div().isInstanceOf[Focusable])
            assert(!SpanElement().isInstanceOf[Focusable])
        }

        "HasDisabled elements" in {
            assert(Button().isInstanceOf[HasDisabled])
            assert(Input().isInstanceOf[HasDisabled])
            assert(Textarea().isInstanceOf[HasDisabled])
            assert(Select().isInstanceOf[HasDisabled])
            assert(!Anchor().isInstanceOf[HasDisabled])
            assert(!Div().isInstanceOf[HasDisabled])
        }

        "TextInput elements" in {
            assert(Input().isInstanceOf[TextInput])
            assert(Textarea().isInstanceOf[TextInput])
            assert(!Button().isInstanceOf[TextInput])
            assert(!Select().isInstanceOf[TextInput])
        }

        "Activatable elements" in {
            assert(Button().isInstanceOf[Activatable])
            assert(Anchor().isInstanceOf[Activatable])
            assert(!Input().isInstanceOf[Activatable])
        }

        "Clickable elements" in {
            assert(Button().isInstanceOf[Clickable])
            assert(Anchor().isInstanceOf[Clickable])
            assert(!Input().isInstanceOf[Clickable])
        }
    }

    "Element" - {
        "factory constructors" in {
            assert(UI.div.isInstanceOf[Div])
            assert(UI.button.isInstanceOf[Button])
            assert(UI.input.isInstanceOf[Input])
            assert(UI.textarea.isInstanceOf[Textarea])
            assert(UI.a.isInstanceOf[Anchor])
            assert(UI.span.isInstanceOf[SpanElement])
            assert(UI.form.isInstanceOf[Form])
            assert(UI.select.isInstanceOf[Select])
            assert(UI.option.isInstanceOf[Opt])
            assert(UI.label.isInstanceOf[Label])
            assert(UI.table.isInstanceOf[Table])
            assert(UI.tr.isInstanceOf[Tr])
            assert(UI.td.isInstanceOf[Td])
            assert(UI.th.isInstanceOf[Th])
            assert(UI.hr.isInstanceOf[Hr])
            assert(UI.br.isInstanceOf[Br])
        }

        "apply adds children" in {
            val el = UI.div("hello", "world")
            assert(el.children.size == 2)
        }

        "apply chains" in {
            val el = UI.div("a")("b")
            assert(el.children.size == 2)
        }

        "img factory" in {
            val el = UI.img(ImgSrc.Path("photo.jpg"), "A photo")
            assert(el.isInstanceOf[Img])
            assert(el.src == Present(ImgSrc.Path("photo.jpg")))
            assert(el.alt == Present("A photo"))
        }

        "id" in {
            val el = UI.div.id("test")
            assert(el.attrs.identifier == Present("test"))
        }

        "hidden" in {
            val el = UI.div.hidden(true)
            assert(el.attrs.hidden.isDefined)
        }

        "tabIndex" in {
            val el = UI.div.tabIndex(1)
            assert(el.attrs.tabIndex == Present(1))
        }

        "style accumulates" in {
            val el = UI.div.style(Style.bold).style(Style.italic)
            val s  = el.attrs.uiStyle
            assert(s.props.size == 2)
        }

        "onClick" in {
            val el = UI.button.onClick(())
            assert(el.attrs.onClick.isDefined)
        }

        "value" in {
            val el = UI.input.value("text")
            assert(el.value.isDefined)
        }

        "placeholder" in {
            val el = UI.input.placeholder("Type here")
            assert(el.placeholder == Present("Type here"))
        }

        "disabled" in {
            val el = UI.button.disabled(true)
            assert(el.disabled.isDefined)
        }

        "checked" in {
            val el = UI.checkbox.checked(true)
            assert(el.checked.isDefined)
        }

        "selected" in {
            val el = UI.option.selected(true)
            assert(el.selected.isDefined)
        }

        "href" in {
            val el = UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow))
            assert(el.href.isDefined)
        }

        "target via href" in {
            val el = UI.a.href(Href.Absolute(HttpUrl.parse("https://example.com").getOrThrow), Target.Blank)
            assert(el.target == Present(Target.Blank))
            assert(el.href.isDefined)
        }

        "colspan and rowspan" in {
            val el = UI.td.colspan(2).rowspan(3)
            assert(el.colspan == Present(2))
            assert(el.rowspan == Present(3))
        }

        "forId and for" in {
            val el1 = UI.label.forId("input1")
            val el2 = UI.label.`for`("input2")
            assert(el1.forId == Present("input1"))
            assert(el2.forId == Present("input2"))
        }

        "password type" in {
            val el = UI.passwordInput.value("secret")
            assert(el.isInstanceOf[PasswordInput])
            assert(el.value.isDefined)
        }

        "onSubmit" in {
            val el = UI.form.onSubmit(())
            assert(el.onSubmit.isDefined)
        }

        "DSL chaining" in {
            val el = UI.button("Click").onClick(()).disabled(false)
            assert(el.isInstanceOf[Button])
            assert(el.children.size == 1)
            assert(el.attrs.onClick.isDefined)
            assert(el.disabled.isDefined)
        }

        "div with style and children" in {
            val el = UI.div.style(Style.row)(UI.span("A"), UI.span("B"))
            assert(el.isInstanceOf[Div])
            assert(el.children.size == 2)
        }
    }

    "AST cases" - {
        "Text" in {
            val t = Text("hello")
            assert(t.value == "hello")
        }

        "Fragment" in {
            val f = Fragment(Chunk(Text("a"), Text("b")))
            assert(f.children.size == 2)
        }

        "fragment factory" in {
            val f = UI.fragment(UI.div, UI.span)
            assert(f.isInstanceOf[Fragment[?]])
        }

        "empty" in {
            given Frame = Frame.derive
            assert(UI.empty.isInstanceOf[Fragment[?]])
        }
    }

    "conversions" - {
        "String to UI" in {
            val ui: UI = "hello"
            ui match
                case t: UI.Ast.Text => assert(t.value == "hello")
                case other          => fail(s"Expected UI.Ast.Text, got ${other.getClass.getSimpleName}")
        }

        "String in Element children" in {
            val el = UI.div("hello")
            assert(el.children(0).isInstanceOf[UI.Ast.Text])
        }
    }

    "Keyboard" - {
        "fromString named keys" in {
            assert(Keyboard.fromString("Enter") == Keyboard.Enter)
            assert(Keyboard.fromString("ArrowUp") == Keyboard.ArrowUp)
            assert(Keyboard.fromString(" ") == Keyboard.Space)
            assert(Keyboard.fromString("F12") == Keyboard.F12)
        }

        "fromString characters" in {
            assert(Keyboard.fromString("a") == Keyboard.Char('a'))
            assert(Keyboard.fromString("1") == Keyboard.Char('1'))
        }

        "fromString unknown" in {
            assert(Keyboard.fromString("Unidentified") == Keyboard.Unknown("Unidentified"))
        }

        "charValue" in {
            assert(Keyboard.Char('a').charValue == kyo.Maybe.Present("a"))
            assert(Keyboard.Space.charValue == kyo.Maybe.Present(" "))
            assert(Keyboard.Enter.charValue == kyo.Maybe.Absent)
        }
    }

    "KeyboardEvent" - {
        "defaults" in {
            val ke = KeyboardEvent(Keyboard.Enter, Modifiers.none, Absent)
            assert(ke.key == Keyboard.Enter)
            assert(!ke.modifiers.ctrl)
            assert(!ke.modifiers.alt)
            assert(!ke.modifiers.shift)
            assert(!ke.modifiers.meta)
        }

        "with modifiers" in {
            val ke = KeyboardEvent(Keyboard.Char('c'), Modifiers(ctrl = true), Absent)
            assert(ke.modifiers.ctrl)
        }

        "equality" in {
            assert(KeyboardEvent(Keyboard.Char('a'), Modifiers.none, Absent) ==
                KeyboardEvent(Keyboard.Char('a'), Modifiers.none, Absent))
            assert(KeyboardEvent(Keyboard.Char('a'), Modifiers.none, Absent) !=
                KeyboardEvent(Keyboard.Char('b'), Modifiers.none, Absent))
        }
    }

    "Typed href/src ADT: compile-time rejection" - {
        "href does not accept raw String" in {
            assertTypeError("""UI.a.href("raw string")""")
        }

        "img factory does not accept raw String src" in {
            assertTypeError("""UI.img(src = "x", alt = "y")""")
        }
    }

end UIModelTest
