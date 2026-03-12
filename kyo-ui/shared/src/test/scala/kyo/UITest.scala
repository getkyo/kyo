package kyo

import UI.internal.*
import scala.language.implicitConversions

class UITest extends Test:

    "Type hierarchy" - {
        "Block elements" in {
            assert(Div().isInstanceOf[UI.Block])
            assert(P().isInstanceOf[UI.Block])
            assert(Section().isInstanceOf[UI.Block])
            assert(Main().isInstanceOf[UI.Block])
            assert(Header().isInstanceOf[UI.Block])
            assert(Footer().isInstanceOf[UI.Block])
            assert(Form().isInstanceOf[UI.Block])
            assert(Pre().isInstanceOf[UI.Block])
            assert(Code().isInstanceOf[UI.Block])
            assert(Ul().isInstanceOf[UI.Block])
            assert(Ol().isInstanceOf[UI.Block])
            assert(Table().isInstanceOf[UI.Block])
            assert(H1().isInstanceOf[UI.Block])
            assert(H2().isInstanceOf[UI.Block])
            assert(H3().isInstanceOf[UI.Block])
            assert(H4().isInstanceOf[UI.Block])
            assert(H5().isInstanceOf[UI.Block])
            assert(H6().isInstanceOf[UI.Block])
            assert(Hr().isInstanceOf[UI.Block])
            assert(Br().isInstanceOf[UI.Block])
            assert(Textarea().isInstanceOf[UI.Block])
            assert(Select().isInstanceOf[UI.Block])
            assert(Td().isInstanceOf[UI.Block])
            assert(Th().isInstanceOf[UI.Block])
            assert(Label().isInstanceOf[UI.Block])
            assert(Opt().isInstanceOf[UI.Block])
        }

        "Inline elements" in {
            assert(Span().isInstanceOf[UI.Inline])
            assert(Nav().isInstanceOf[UI.Inline])
            assert(Li().isInstanceOf[UI.Inline])
            assert(Tr().isInstanceOf[UI.Inline])
            assert(Button().isInstanceOf[UI.Inline])
            assert(Input().isInstanceOf[UI.Inline])
            assert(Anchor().isInstanceOf[UI.Inline])
            assert(Img().isInstanceOf[UI.Inline])
        }

        "Focusable elements" in {
            assert(Button().isInstanceOf[UI.Focusable])
            assert(Input().isInstanceOf[UI.Focusable])
            assert(Textarea().isInstanceOf[UI.Focusable])
            assert(Select().isInstanceOf[UI.Focusable])
            assert(Anchor().isInstanceOf[UI.Focusable])
            assert(!Div().isInstanceOf[UI.Focusable])
            assert(!Span().isInstanceOf[UI.Focusable])
        }

        "HasDisabled elements" in {
            assert(Button().isInstanceOf[UI.HasDisabled])
            assert(Input().isInstanceOf[UI.HasDisabled])
            assert(Textarea().isInstanceOf[UI.HasDisabled])
            assert(Select().isInstanceOf[UI.HasDisabled])
            assert(!Anchor().isInstanceOf[UI.HasDisabled])
            assert(!Div().isInstanceOf[UI.HasDisabled])
        }

        "TextInput elements" in {
            assert(Input().isInstanceOf[UI.TextInput])
            assert(Textarea().isInstanceOf[UI.TextInput])
            assert(!Button().isInstanceOf[UI.TextInput])
            assert(!Select().isInstanceOf[UI.TextInput])
        }

        "Activatable elements" in {
            assert(Button().isInstanceOf[UI.Activatable])
            assert(Anchor().isInstanceOf[UI.Activatable])
            assert(!Input().isInstanceOf[UI.Activatable])
        }

        "Clickable elements" in {
            assert(Button().isInstanceOf[UI.Clickable])
            assert(Anchor().isInstanceOf[UI.Clickable])
            assert(!Input().isInstanceOf[UI.Clickable])
        }
    }

    "Element" - {
        "factory constructors" in {
            assert(UI.div.isInstanceOf[UI.Div])
            assert(UI.button.isInstanceOf[UI.Button])
            assert(UI.input.isInstanceOf[UI.Input])
            assert(UI.textarea.isInstanceOf[UI.Textarea])
            assert(UI.a.isInstanceOf[UI.Anchor])
            assert(UI.span.isInstanceOf[UI.Span])
            assert(UI.form.isInstanceOf[UI.Form])
            assert(UI.select.isInstanceOf[UI.Select])
            assert(UI.option.isInstanceOf[UI.Opt])
            assert(UI.label.isInstanceOf[UI.Label])
            assert(UI.table.isInstanceOf[UI.Table])
            assert(UI.tr.isInstanceOf[UI.Tr])
            assert(UI.td.isInstanceOf[UI.Td])
            assert(UI.th.isInstanceOf[UI.Th])
            assert(UI.hr.isInstanceOf[UI.Hr])
            assert(UI.br.isInstanceOf[UI.Br])
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
            val el = UI.img("photo.jpg", "A photo")
            assert(el.isInstanceOf[UI.Img])
            assert(el.src.isDefined && el.src.get.isInstanceOf[String] && el.src.get.asInstanceOf[String] == "photo.jpg")
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
            // unsafe: asInstanceOf — test knows the static style type
            val s = el.attrs.uiStyle.asInstanceOf[Style]
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
            val el = UI.a.href("https://example.com")
            assert(el.href.isDefined)
        }

        "target via href" in {
            val el = UI.a.href("https://example.com", UI.Target.Blank)
            assert(el.target == Present(UI.Target.Blank))
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
            val el = UI.password.value("secret")
            assert(el.isInstanceOf[UI.Password])
            assert(el.value.isDefined)
        }

        "onSubmit" in {
            val el = UI.form.onSubmit(())
            assert(el.onSubmit.isDefined)
        }

        "DSL chaining" in {
            val el = UI.button("Click").onClick(()).disabled(false)
            assert(el.isInstanceOf[UI.Button])
            assert(el.children.size == 1)
            assert(el.attrs.onClick.isDefined)
            assert(el.disabled.isDefined)
        }

        "div with style and children" in {
            val el = UI.div.style(Style.row)(UI.span("A"), UI.span("B"))
            assert(el.isInstanceOf[UI.Div])
            assert(el.children.size == 2)
        }
    }

    "AST cases" - {
        "Text" in {
            val t = Text("hello")
            assert(t.value == "hello")
        }

        "Fragment" in {
            val f = Fragment(kyo.Span.from(Array[UI](Text("a"), Text("b"))))
            assert(f.children.size == 2)
        }

        "fragment factory" in {
            val f = UI.fragment(UI.div, UI.span)
            assert(f.isInstanceOf[UI.Fragment])
        }

        "empty" in {
            assert(UI.empty.isInstanceOf[UI.Fragment])
        }
    }

    "conversions" - {
        "String to UI" in {
            val ui: UI = "hello"
            assert(ui.isInstanceOf[UI.Text])
            // unsafe: asInstanceOf — test verifies conversion result type
            assert(ui.asInstanceOf[UI.Text].value == "hello")
        }

        "String in Element children" in {
            val el = UI.div("hello")
            assert(el.children(0).isInstanceOf[UI.Text])
        }
    }

    "Keyboard" - {
        "fromString named keys" in {
            assert(UI.Keyboard.fromString("Enter") == UI.Keyboard.Enter)
            assert(UI.Keyboard.fromString("ArrowUp") == UI.Keyboard.ArrowUp)
            assert(UI.Keyboard.fromString(" ") == UI.Keyboard.Space)
            assert(UI.Keyboard.fromString("F12") == UI.Keyboard.F12)
        }

        "fromString characters" in {
            assert(UI.Keyboard.fromString("a") == UI.Keyboard.Char('a'))
            assert(UI.Keyboard.fromString("1") == UI.Keyboard.Char('1'))
        }

        "fromString unknown" in {
            assert(UI.Keyboard.fromString("Unidentified") == UI.Keyboard.Unknown("Unidentified"))
        }

        "charValue" in {
            assert(UI.Keyboard.Char('a').charValue == kyo.Maybe.Present("a"))
            assert(UI.Keyboard.Space.charValue == kyo.Maybe.Present(" "))
            assert(UI.Keyboard.Enter.charValue == kyo.Maybe.Absent)
        }
    }

    "KeyEvent" - {
        "defaults" in {
            val ke = UI.KeyEvent(UI.Keyboard.Enter)
            assert(ke.key == UI.Keyboard.Enter)
            assert(!ke.ctrl)
            assert(!ke.alt)
            assert(!ke.shift)
            assert(!ke.meta)
        }

        "with modifiers" in {
            val ke = UI.KeyEvent(UI.Keyboard.Char('c'), ctrl = true)
            assert(ke.ctrl)
        }

        "equality" in {
            assert(UI.KeyEvent(UI.Keyboard.Char('a')) == UI.KeyEvent(UI.Keyboard.Char('a')))
            assert(UI.KeyEvent(UI.Keyboard.Char('a')) != UI.KeyEvent(UI.Keyboard.Char('b')))
        }
    }

end UITest
