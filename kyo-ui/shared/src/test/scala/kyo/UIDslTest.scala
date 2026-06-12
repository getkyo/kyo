package kyo

import UI.*
import UI.Ast.*

class UIDslTest extends kyo.test.Test[Any]:

    "div builder" in {
        val result = div("hello")
        assert(result.isInstanceOf[Div])
    }

    "button builder" in {
        val result = button("click me")
        assert(result.isInstanceOf[Button])
    }

    "input builder" in {
        val result = input
        assert(result.isInstanceOf[Input])
    }

    "anchor builder" in {
        val result = a
        assert(result.isInstanceOf[Anchor])
    }

    "text conversion via string implicit" in {
        val result: UI = "hello"
        assert(result.isInstanceOf[Text])
        assert(result.asInstanceOf[Text].value == "hello")
    }

    "fragment builder" in {
        val result = fragment(div("a"), div("b"))
        assert(result.isInstanceOf[Fragment[?]])
        assert(result.children.size == 2)
    }

    "div with id propagates id attribute" in {
        val result = div("x").id("myDiv")
        assert(result.attrs.identifier == Present("myDiv"))
    }

    "button with id propagates id attribute" in {
        val result = button("ok").id("myBtn")
        assert(result.attrs.identifier == Present("myBtn"))
    }

    "px extension available from kyo.*" in {
        val l: Length.Px = 10.px
        assert(l == Length.Px(10))
    }

    "Style.Color is accessible" in {
        val c = Style.Color.Hex("#ff0000")
        assert(c.isInstanceOf[Style.Color.Hex])
    }

    "nested div contains children" in {
        val result = div(
            button("A"),
            button("B")
        )
        assert(result.children.size == 2)
    }

    "input with const value via .value" in {
        val result = input.value("hello")
        assert(result.value.isDefined)
    }

end UIDslTest
