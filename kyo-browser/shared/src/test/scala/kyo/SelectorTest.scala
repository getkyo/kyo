package kyo

import kyo.Browser.Selector.find
import kyo.Browser.Selector.or
import kyo.internal.SelectorNode

class SelectorTest extends BaseBrowserTest:

    // CanEqual instance for strict equality
    given CanEqual[SelectorNode, SelectorNode] = CanEqual.derived

    // ── Constructors ────────────────────────────────────────────────────

    "button no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.button) == SelectorNode.Aria("button", ""))
    }

    "button with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.button("Save")) == SelectorNode.Aria("button", "Save"))
    }

    "textbox no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.textbox) == SelectorNode.Aria("textbox", ""))
    }

    "textbox with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.textbox("Email")) == SelectorNode.Aria("textbox", "Email"))
    }

    "link no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.link) == SelectorNode.Aria("link", ""))
    }

    "link with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.link("Home")) == SelectorNode.Aria("link", "Home"))
    }

    "checkbox no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.checkbox) == SelectorNode.Aria("checkbox", ""))
    }

    "checkbox with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.checkbox("Agree")) == SelectorNode.Aria("checkbox", "Agree"))
    }

    "combobox no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.combobox) == SelectorNode.Aria("combobox", ""))
    }

    "combobox with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.combobox("Country")) == SelectorNode.Aria("combobox", "Country"))
    }

    "listbox no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.listbox) == SelectorNode.Aria("listbox", ""))
    }

    "listbox with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.listbox("Options")) == SelectorNode.Aria("listbox", "Options"))
    }

    "radio no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.radio) == SelectorNode.Aria("radio", ""))
    }

    "radio with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.radio("Option A")) == SelectorNode.Aria("radio", "Option A"))
    }

    "dialog no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.dialog) == SelectorNode.Aria("dialog", ""))
    }

    "dialog with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.dialog("Login")) == SelectorNode.Aria("dialog", "Login"))
    }

    "heading no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.heading) == SelectorNode.Aria("heading", ""))
    }

    "heading with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.heading("Welcome")) == SelectorNode.Aria("heading", "Welcome"))
    }

    "tab no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.tab) == SelectorNode.Aria("tab", ""))
    }

    "tab with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.tab("Settings")) == SelectorNode.Aria("tab", "Settings"))
    }

    "menuitem no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.menuitem) == SelectorNode.Aria("menuitem", ""))
    }

    "menuitem with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.menuitem("Copy")) == SelectorNode.Aria("menuitem", "Copy"))
    }

    "form no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.form) == SelectorNode.Aria("form", ""))
    }

    "form with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.form("Login Form")) == SelectorNode.Aria("form", "Login Form"))
    }

    "img no-arg" in {
        assert(Browser.Selector.toNode(Browser.Selector.img) == SelectorNode.Aria("img", ""))
    }

    "img with name" in {
        assert(Browser.Selector.toNode(Browser.Selector.img("Logo")) == SelectorNode.Aria("img", "Logo"))
    }

    "text with default exact=false" in {
        assert(Browser.Selector.toNode(Browser.Selector.text("hello")) == SelectorNode.Text("hello", false))
    }

    "text with exact=true" in {
        assert(Browser.Selector.toNode(Browser.Selector.text("hello", exact = true)) == SelectorNode.Text("hello", true))
    }

    "id" in {
        assert(Browser.Selector.toNode(Browser.Selector.id("foo")) == SelectorNode.Id("foo"))
    }

    "css" in {
        assert(Browser.Selector.toNode(Browser.Selector.css(".bar")) == SelectorNode.Css(".bar"))
    }

    // ── Composition ─────────────────────────────────────────────────────

    "Selector composes via .or and .find through the Browser companion" in {
        // Witness that Selector's combinators resolve through `Browser.Selector`.
        val byRole: Browser.Selector = Browser.Selector.button("Save")
        val byCss: Browser.Selector  = Browser.Selector.css("button.save")
        val combined                 = byRole.or(byCss)
        Browser.Selector.toNode(combined) match
            case SelectorNode.FirstOf(selectors) =>
                assert(selectors.size == 2)
                assert(selectors(0) == SelectorNode.Aria("button", "Save"))
                assert(selectors(1) == SelectorNode.Css("button.save"))
            case other => fail(s"Expected FirstOf via Browser.Selector, got $other")
        end match
        val nested = byRole.find(Browser.Selector.text("Save"))
        Browser.Selector.toNode(nested) match
            case SelectorNode.Within(parent, child) =>
                assert(parent == SelectorNode.Aria("button", "Save"))
                assert(child == SelectorNode.Text("Save", false))
            case other => fail(s"Expected Within via Browser.Selector.find, got $other")
        end match
    }

    "a.or(b) creates FirstOf" in {
        val a = Browser.Selector.button("A")
        val b = Browser.Selector.button("B")
        Browser.Selector.toNode(a.or(b)) match
            case SelectorNode.FirstOf(selectors) =>
                assert(selectors.size == 2)
                assert(selectors(0) == SelectorNode.Aria("button", "A"))
                assert(selectors(1) == SelectorNode.Aria("button", "B"))
            case other => fail(s"Expected FirstOf, got $other")
        end match
    }

    "a.or(b).or(c) flattens to single FirstOf" in {
        val a = Browser.Selector.button("A")
        val b = Browser.Selector.button("B")
        val c = Browser.Selector.button("C")
        Browser.Selector.toNode(a.or(b).or(c)) match
            case SelectorNode.FirstOf(selectors) =>
                assert(selectors.size == 3)
                assert(selectors(0) == SelectorNode.Aria("button", "A"))
                assert(selectors(1) == SelectorNode.Aria("button", "B"))
                assert(selectors(2) == SelectorNode.Aria("button", "C"))
            case other => fail(s"Expected FirstOf with 3 elements, got $other")
        end match
    }

    "FirstOf(a,b).or(FirstOf(c,d)) merges" in {
        val ab = Browser.Selector.button("A").or(Browser.Selector.button("B"))
        val cd = Browser.Selector.button("C").or(Browser.Selector.button("D"))
        Browser.Selector.toNode(ab.or(cd)) match
            case SelectorNode.FirstOf(selectors) =>
                assert(selectors.size == 4)
                assert(selectors(0) == SelectorNode.Aria("button", "A"))
                assert(selectors(1) == SelectorNode.Aria("button", "B"))
                assert(selectors(2) == SelectorNode.Aria("button", "C"))
                assert(selectors(3) == SelectorNode.Aria("button", "D"))
            case other => fail(s"Expected FirstOf with 4 elements, got $other")
        end match
    }

    "single.or(FirstOf(a,b)) prepends" in {
        val single = Browser.Selector.button("X")
        val ab     = Browser.Selector.button("A").or(Browser.Selector.button("B"))
        Browser.Selector.toNode(single.or(ab)) match
            case SelectorNode.FirstOf(selectors) =>
                assert(selectors.size == 3)
                assert(selectors(0) == SelectorNode.Aria("button", "X"))
                assert(selectors(1) == SelectorNode.Aria("button", "A"))
                assert(selectors(2) == SelectorNode.Aria("button", "B"))
            case other => fail(s"Expected FirstOf with 3 elements, got $other")
        end match
    }

    "a.find(b) creates Within" in {
        val a = Browser.Selector.dialog("Login")
        val b = Browser.Selector.button("Sign in")
        Browser.Selector.toNode(a.find(b)) match
            case SelectorNode.Within(parent, child) =>
                assert(parent == SelectorNode.Aria("dialog", "Login"))
                assert(child == SelectorNode.Aria("button", "Sign in"))
            case other => fail(s"Expected Within, got $other")
        end match
    }

    "nested Within: dialog.find(form.find(textbox))" in {
        val selector = Browser.Selector.dialog("Login").find(Browser.Selector.form.find(Browser.Selector.textbox("Email")))
        Browser.Selector.toNode(selector) match
            case SelectorNode.Within(parent, child) =>
                assert(parent == SelectorNode.Aria("dialog", "Login"))
                child match
                    case SelectorNode.Within(innerParent, innerChild) =>
                        assert(innerParent == SelectorNode.Aria("form", ""))
                        assert(innerChild == SelectorNode.Aria("textbox", "Email"))
                    case other => fail(s"Expected inner Within, got $other")
                end match
            case other => fail(s"Expected outer Within, got $other")
        end match
    }

    // ── toNode / fromNode ───────────────────────────────────────────────

    "toNode and fromNode round-trip" in {
        val node     = SelectorNode.Aria("button", "OK")
        val selector = Browser.Selector.fromNode(node)
        assert(Browser.Selector.toNode(selector) == node)
    }

    "fromNode and toNode for Css" in {
        val node     = SelectorNode.Css("div.container")
        val selector = Browser.Selector.fromNode(node)
        assert(Browser.Selector.toNode(selector) == node)
    }

    "fromNode and toNode for Text" in {
        val node     = SelectorNode.Text("hello world", true)
        val selector = Browser.Selector.fromNode(node)
        assert(Browser.Selector.toNode(selector) == node)
    }

    "fromNode and toNode for Id" in {
        val node     = SelectorNode.Id("main-content")
        val selector = Browser.Selector.fromNode(node)
        assert(Browser.Selector.toNode(selector) == node)
    }

    "fromNode and toNode for FirstOf" in {
        val node     = SelectorNode.FirstOf(Chunk(SelectorNode.Css("a"), SelectorNode.Css("b")))
        val selector = Browser.Selector.fromNode(node)
        assert(Browser.Selector.toNode(selector) == node)
    }

    "fromNode and toNode for Within" in {
        val node     = SelectorNode.Within(SelectorNode.Css("div"), SelectorNode.Css("span"))
        val selector = Browser.Selector.fromNode(node)
        assert(Browser.Selector.toNode(selector) == node)
    }

    // ── Selector kinds ─────────────────────────────────────────

    "Selector.testId builds SelectorNode.TestId" in {
        assert(Browser.Selector.toNode(Browser.Selector.testId("login")) == SelectorNode.TestId("login"))
    }

    "Selector.label builds SelectorNode.Label" in {
        assert(Browser.Selector.toNode(Browser.Selector.label("Email")) == SelectorNode.Label("Email"))
    }

    "Selector.placeholder builds SelectorNode.Placeholder" in {
        assert(Browser.Selector.toNode(Browser.Selector.placeholder("Search")) == SelectorNode.Placeholder("Search"))
    }

    "Selector.title builds SelectorNode.Title" in {
        assert(Browser.Selector.toNode(Browser.Selector.title("Close")) == SelectorNode.Title("Close"))
    }

    "selectorDescription renders testId, label, placeholder, title with their payloads" in {
        // Access the private selectorDescription via Selector.toNode.toString fallback; but the new kinds get a dedicated
        // description in Browser.scala's selectorNodeDescription. Here we exercise the public AST-level rendering only;
        // human-readable descriptions are validated indirectly in SelectorBugTest via error messages.
        val t = Browser.Selector.toNode(Browser.Selector.testId("btn"))
        val l = Browser.Selector.toNode(Browser.Selector.label("Name"))
        val p = Browser.Selector.toNode(Browser.Selector.placeholder("Query"))
        val h = Browser.Selector.toNode(Browser.Selector.title("Home"))
        assert(t == SelectorNode.TestId("btn"))
        assert(l == SelectorNode.Label("Name"))
        assert(p == SelectorNode.Placeholder("Query"))
        assert(h == SelectorNode.Title("Home"))
    }

    // ── Prefixed-string DSL routing ─────────────────────────────────────
    //
    // The implicit `Conversion[String, Selector]` routes a small prefix DSL to typed nodes:
    //   text=… → Text(…, exact=false), testid=… → TestId(…), label=… → Label(…),
    //   id=… → Id(…), css=… → Css(…). Anything else falls back to Css verbatim,
    //   including unknown-prefix strings like `abc=def` (the `abc=` is preserved).

    "string \"text=Sign in\" parses to SelectorNode.Text(\"Sign in\", false)" in {
        val sel = summon[Conversion[String, Browser.Selector]].apply("text=Sign in")
        assert(Browser.Selector.toNode(sel) == SelectorNode.Text("Sign in", false))
    }

    "string \"testid=login-form\" parses to SelectorNode.TestId(\"login-form\")" in {
        val sel = summon[Conversion[String, Browser.Selector]].apply("testid=login-form")
        assert(Browser.Selector.toNode(sel) == SelectorNode.TestId("login-form"))
    }

    "string \"label=Email\" parses to SelectorNode.Label(\"Email\")" in {
        val sel = summon[Conversion[String, Browser.Selector]].apply("label=Email")
        assert(Browser.Selector.toNode(sel) == SelectorNode.Label("Email"))
    }

    "string \"id=submit\" parses to SelectorNode.Id(\"submit\")" in {
        val sel = summon[Conversion[String, Browser.Selector]].apply("id=submit")
        assert(Browser.Selector.toNode(sel) == SelectorNode.Id("submit"))
    }

    "string \"css=.btn-primary\" parses to SelectorNode.Css(\".btn-primary\")" in {
        val sel = summon[Conversion[String, Browser.Selector]].apply("css=.btn-primary")
        assert(Browser.Selector.toNode(sel) == SelectorNode.Css(".btn-primary"))
    }

    "string \"abc=def\" parses to SelectorNode.Css(\"abc=def\") (unknown prefix verbatim)" in {
        val sel = summon[Conversion[String, Browser.Selector]].apply("abc=def")
        assert(Browser.Selector.toNode(sel) == SelectorNode.Css("abc=def"))
    }

    "string \".btn-primary\" parses to SelectorNode.Css(\".btn-primary\") (no prefix)" in {
        val sel = summon[Conversion[String, Browser.Selector]].apply(".btn-primary")
        assert(Browser.Selector.toNode(sel) == SelectorNode.Css(".btn-primary"))
    }

    // ── escapeCssIdent fixture round-trip ───────────────
    "escapeCssIdent escapes identifiers correctly" in {
        val golden = List(
            "hello" -> "hello",
            "a-b"   -> "a-b",
            "123"   -> "\\31 23",
            "a:b"   -> "a\\:b",
            "a.b"   -> "a\\.b",
            "a\"b"  -> "a\\\"b",
            "a\\b"  -> "a\\\\b",
            ""      -> ""
        )
        golden.foreach { case (input, expected) =>
            val actual = kyo.internal.SelectorJs.escapeCssIdent(input)
            assert(actual == expected, s"escapeCssIdent($input) = '$actual', expected '$expected'")
        }
        ()
    }

end SelectorTest
