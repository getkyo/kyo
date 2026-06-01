package kyo.internal

import kyo.*
import scala.annotation.tailrec

/** A composable description of how to locate one or more elements in a browser page.
  *
  * `Selector` is an opaque view over [[SelectorNode]], the internal AST. Constructors yield leaf selectors (ARIA roles, CSS, text, IDs,
  * labels), and the [[Selector.or]] / [[Selector.find]] extensions combine them into composite expressions. The resulting tree is
  * interpreted by the browser engine when an action such as `Browser.click` runs.
  *
  * Three families of constructors are provided:
  *
  *   - ARIA-role builders (e.g. [[Selector.button]], [[Selector.textbox]]) that match by accessibility role, optionally narrowed by an
  *     accessible name.
  *   - Direct locators ([[Selector.css]], [[Selector.id]], [[Selector.text]], [[Selector.testId]], [[Selector.label]],
  *     [[Selector.placeholder]], [[Selector.title]]).
  *   - Combinators ([[Selector.or]] for fallback chains, [[Selector.find]] for child scoping).
  *
  * Selectors are values: building one is pure and side-effect free. Resolution to actual DOM elements happens only when a `Browser` action
  * evaluates the selector inside the running page.
  *
  * @see
  *   [[kyo.Browser]] for actions that consume selectors.
  * @see
  *   [[kyo.BrowserElementNotFoundException]] for the failure raised when no element matches.
  * @see
  *   [[kyo.BrowserElementNotActionableException]] for matched-but-unusable elements.
  * @see
  *   [[kyo.Browser.Key]] for keystrokes dispatched after locating an element.
  */
opaque type Selector = SelectorNode

/** Companion containing selector constructors and the `or` / `find` combinators. */
object Selector:

    // --- ARIA constructors ---

    /** Matches any element with the ARIA `button` role. */
    def button: Selector = SelectorNode.Aria("button", "")

    /** Matches a `button`-role element whose accessible name equals `name`. */
    def button(name: String): Selector = SelectorNode.Aria("button", name)

    /** Matches any element with the ARIA `textbox` role. */
    def textbox: Selector = SelectorNode.Aria("textbox", "")

    /** Matches a `textbox`-role element whose accessible name equals `name`. */
    def textbox(name: String): Selector = SelectorNode.Aria("textbox", name)

    /** Matches any element with the ARIA `link` role. */
    def link: Selector = SelectorNode.Aria("link", "")

    /** Matches a `link`-role element whose accessible name equals `name`. */
    def link(name: String): Selector = SelectorNode.Aria("link", name)

    /** Matches any element with the ARIA `checkbox` role. */
    def checkbox: Selector = SelectorNode.Aria("checkbox", "")

    /** Matches a `checkbox`-role element whose accessible name equals `name`. */
    def checkbox(name: String): Selector = SelectorNode.Aria("checkbox", name)

    /** Matches any element with the ARIA `combobox` role. */
    def combobox: Selector = SelectorNode.Aria("combobox", "")

    /** Matches a `combobox`-role element whose accessible name equals `name`. */
    def combobox(name: String): Selector = SelectorNode.Aria("combobox", name)

    /** Matches any element with the ARIA `listbox` role. */
    def listbox: Selector = SelectorNode.Aria("listbox", "")

    /** Matches a `listbox`-role element whose accessible name equals `name`. */
    def listbox(name: String): Selector = SelectorNode.Aria("listbox", name)

    /** Matches any element with the ARIA `radio` role. */
    def radio: Selector = SelectorNode.Aria("radio", "")

    /** Matches a `radio`-role element whose accessible name equals `name`. */
    def radio(name: String): Selector = SelectorNode.Aria("radio", name)

    /** Matches any element with the ARIA `dialog` role. */
    def dialog: Selector = SelectorNode.Aria("dialog", "")

    /** Matches a `dialog`-role element whose accessible name equals `name`. */
    def dialog(name: String): Selector = SelectorNode.Aria("dialog", name)

    /** Matches any element with the ARIA `heading` role. */
    def heading: Selector = SelectorNode.Aria("heading", "")

    /** Matches a `heading`-role element whose accessible name equals `name`. */
    def heading(name: String): Selector = SelectorNode.Aria("heading", name)

    /** Matches any element with the ARIA `tab` role. */
    def tab: Selector = SelectorNode.Aria("tab", "")

    /** Matches a `tab`-role element whose accessible name equals `name`. */
    def tab(name: String): Selector = SelectorNode.Aria("tab", name)

    /** Matches any element with the ARIA `menuitem` role. */
    def menuitem: Selector = SelectorNode.Aria("menuitem", "")

    /** Matches a `menuitem`-role element whose accessible name equals `name`. */
    def menuitem(name: String): Selector = SelectorNode.Aria("menuitem", name)

    /** Matches any element with the ARIA `form` role. */
    def form: Selector = SelectorNode.Aria("form", "")

    /** Matches a `form`-role element whose accessible name equals `name`. */
    def form(name: String): Selector = SelectorNode.Aria("form", name)

    /** Matches any element with the ARIA `img` role. */
    def img: Selector = SelectorNode.Aria("img", "")

    /** Matches an `img`-role element whose accessible name equals `name`. */
    def img(name: String): Selector = SelectorNode.Aria("img", name)

    // --- Other constructors ---

    /** Matches an element by visible text. When `exact` is `false`, performs a substring match. */
    def text(value: String, exact: Boolean = false): Selector = SelectorNode.Text(value, exact)

    /** Matches an element by its DOM `id` attribute. */
    def id(value: String): Selector = SelectorNode.Id(value)

    /** Matches an element by a raw CSS selector string. */
    def css(value: String): Selector = SelectorNode.Css(value)

    /** Matches an element by its `data-testid` attribute. */
    def testId(value: String): Selector = SelectorNode.TestId(value)

    /** Matches a labeled control by the visible text of its associated `<label>`. */
    def label(text: String): Selector = SelectorNode.Label(text)

    /** Matches an input element by its `placeholder` attribute. */
    def placeholder(text: String): Selector = SelectorNode.Placeholder(text)

    /** Matches an element by its `title` attribute. */
    def title(text: String): Selector = SelectorNode.Title(text)

    // --- Extensions ---

    extension (s: Selector)
        /** Try this selector, fall back to `other` if no element is found. */
        def or(other: Selector): Selector = (s, other) match
            case (SelectorNode.FirstOf(a), SelectorNode.FirstOf(b)) => SelectorNode.FirstOf(a.concat(b))
            case (SelectorNode.FirstOf(a), _)                       => SelectorNode.FirstOf(a.append(other))
            case (_, SelectorNode.FirstOf(b))                       => SelectorNode.FirstOf(Chunk(s).concat(b))
            case _                                                  => SelectorNode.FirstOf(Chunk(s, other))

        /** Find `child` scoped within the element matched by this selector. */
        def find(child: Selector): Selector = SelectorNode.Within(s, child)

        /** Restricts this selector to elements that pass the same visibility filter applied implicitly to ARIA semantic selectors:
          * `Element.checkVisibility({checkVisibilityCSS: true})` AND no `aria-hidden="true"` on the element or any ancestor.
          *
          * The opacity check is intentionally OFF: fully-transparent but hit-testable controls (fade-in animations, loading shimmers) are
          * still considered semantically present, matching the ARIA filter's behavior.
          *
          * Useful for non-ARIA selectors that match multiple layout-conditional copies of the same element (mobile + desktop nav,
          * responsive form variants). Composes with `or` / `find` / `Selector.css` / `Selector.id` / `Selector.testId` / etc. Idempotent:
          * `s.visible.visible` is the same as `s.visible`.
          *
          * ARIA selectors already apply this filter implicitly; wrapping them in `.visible` is harmless but redundant.
          *
          * Example:
          * ```scala
          * Browser.click(Selector.id("searchInput").visible) // skips a hidden mobile-layout copy of the same id
          * ```
          */
        def visible: Selector = s match
            case SelectorNode.Visible(_) => s
            case _                       => SelectorNode.Visible(s)
    end extension

    // --- Internal ---

    private[kyo] def toNode(s: Selector): SelectorNode   = s
    private[kyo] def fromNode(n: SelectorNode): Selector = n

    /** Parses a string into a [[SelectorNode]] honoring a small prefix DSL:
      *
      *   - `text=<value>` routes to [[SelectorNode.Text]] (substring match, `exact = false`).
      *   - `testid=<value>` routes to [[SelectorNode.TestId]].
      *   - `label=<value>` routes to [[SelectorNode.Label]].
      *   - `id=<value>` routes to [[SelectorNode.Id]] (typed Id node, duplicate-id-tolerant via the existing resolver, matching
      *     `Selector.id(...)`).
      *   - `css=<value>` routes to [[SelectorNode.Css]] (explicit form for strings that happen to start with another prefix's literal text).
      *
      * Any unrecognised prefix (for example `abc=def` or `role=button`) is treated as a raw CSS selector verbatim, INCLUDING the prefix
      * itself; `role=` is intentionally excluded so that ARIA lookups go through the typed `Selector.button(name)` / `Selector.textbox(name)`
      * / etc. constructors where the intent reads naturally.
      */
    private[kyo] def parsePrefixed(s: String): SelectorNode =
        if s.startsWith("text=") then SelectorNode.Text(s.drop(5), exact = false)
        else if s.startsWith("testid=") then SelectorNode.TestId(s.drop(7))
        else if s.startsWith("label=") then SelectorNode.Label(s.drop(6))
        else if s.startsWith("id=") then SelectorNode.Id(s.drop(3))
        else if s.startsWith("css=") then SelectorNode.Css(s.drop(4))
        else SelectorNode.Css(s)

    /** Auto-converts a string to a [[Selector]] through the [[parsePrefixed]] DSL: `Browser.click("#go")` is a raw CSS selector and routes
      * to `Selector.css("#go")`, while `Browser.click("text=Sign in")` routes to `Selector.text("Sign in")`. Equivalent to calling
      * [[parsePrefixed]] directly; provided so the common cases read naturally without an explicit constructor. For composition (`or`,
      * `find`) reach for the typed `Selector.*` constructors directly.
      */
    given Conversion[String, Selector] = s => parsePrefixed(s)

end Selector

/** Internal AST behind [[Selector]]. */
private[kyo] enum SelectorNode derives CanEqual:
    case Aria(role: String, name: String)
    case Text(value: String, exact: Boolean)
    case Css(value: String)
    case Id(value: String)
    case TestId(value: String)
    case Label(text: String)
    case Placeholder(text: String)
    case Title(text: String)
    case FirstOf(selectors: Chunk[SelectorNode])
    case Within(parent: SelectorNode, child: SelectorNode)
    case Visible(inner: SelectorNode)
end SelectorNode

/** Pure-string JS expression construction for resolving [[SelectorNode]]s in-page. Every method is a deterministic JS template builder; no
  * effects, no I/O.
  */
private[kyo] object SelectorJs:

    /** Returns a JS expression that resolves to the first matching element, or null if not found. Handles all SelectorNode types with
      * proper JS-based resolution for Text and Aria selectors.
      */
    private[kyo] def resolveElementJs(node: SelectorNode): String =
        resolveElementJsScoped(node, "document")

    /** Like `resolveElementJs` but the search is scoped to the subtree rooted at `rootExpr` (a JS expression evaluating to an Element or
      * Document). When `rootExpr == "document"` this is the document-scoped entry point.
      *
      * Scoped resolution is how `Within(parent, child)` lowers: it resolves the parent element and then runs the child's matcher over the
      * parent's subtree (including the parent itself). This lets non-CSS matchers (`Text`, `Aria`-by-accessible-name, `Label`, etc.)
      * participate correctly in `find(...)` rather than being forced through a CSS-only path that has no equivalent for text/aria matching.
      */
    private def resolveElementJsScoped(node: SelectorNode, rootExpr: String): String =
        node match
            case SelectorNode.Css(v) =>
                val sel = JsStringUtil.escapeJsString(v)
                if rootExpr == "document" then s"document.querySelector('$sel')"
                else
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return null;
                        return _r.querySelector('$sel');
                    })()"""
                end if
            case SelectorNode.Id(v) =>
                val cssEsc = escapeCssIdent(v)
                val jsEsc  = JsStringUtil.escapeJsString(cssEsc)
                if rootExpr == "document" then s"document.getElementById('${JsStringUtil.escapeJsString(v)}')"
                else
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return null;
                        return _r.querySelector('#$jsEsc');
                    })()"""
                end if
            case SelectorNode.Aria(role, name) =>
                val css = implicitRoleCss(role)
                if name.isEmpty then
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return null;
                        $ariaVisibleFn
                        const els = _r.querySelectorAll('$css');
                        for (const el of els) {
                            if (_kyoAriaVisible(el)) return el;
                        }
                        return null;
                    })()"""
                else
                    val escaped = JsStringUtil.escapeJsString(name)
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return null;
                        $ariaVisibleFn
                        const els = _r.querySelectorAll('$css');
                        for (const el of els) {
                            if (!_kyoAriaVisible(el)) continue;
                            const label = el.getAttribute('aria-label') || el.textContent.trim();
                            if (label === '$escaped') return el;
                        }
                        return null;
                    })()"""
                end if
            case SelectorNode.Text(value, exact) =>
                val escaped = JsStringUtil.escapeJsString(value)
                val matchOp = if exact then "=== '" + escaped + "'" else ".includes('" + escaped + "')"
                val trimOp  = if exact then ".trim()" else ""
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return null;
                    const walker = document.createTreeWalker(_r, NodeFilter.SHOW_TEXT);
                    while (walker.nextNode()) {
                        const t = walker.currentNode.textContent$trimOp;
                        if (t$matchOp) return walker.currentNode.parentElement;
                    }
                    const inputs = _r.querySelectorAll('input, textarea');
                    for (const el of inputs) {
                        const p = (el.getAttribute('placeholder') || '')$trimOp;
                        if (p$matchOp) return el;
                    }
                    return null;
                })()"""
            case SelectorNode.TestId(v) =>
                val escaped = JsStringUtil.escapeJsString(v)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return null;
                    return _r.querySelector('[data-testid="$escaped"]');
                })()"""
            case SelectorNode.Label(text) =>
                val escaped = JsStringUtil.escapeJsString(text)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return null;
                    const labels = _r.querySelectorAll('label');
                    for (const lbl of labels) {
                        if (lbl.textContent.trim() !== '$escaped') continue;
                        const forId = lbl.getAttribute('for');
                        if (forId) {
                            const target = document.getElementById(forId);
                            if (target) return target;
                        }
                        const nested = lbl.querySelector('input, textarea, select');
                        if (nested) return nested;
                    }
                    return null;
                })()"""
            case SelectorNode.Placeholder(text) =>
                val escaped = JsStringUtil.escapeJsString(text)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return null;
                    return _r.querySelector('input[placeholder="$escaped"], textarea[placeholder="$escaped"]');
                })()"""
            case SelectorNode.Title(text) =>
                val escaped = JsStringUtil.escapeJsString(text)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return null;
                    return _r.querySelector('[title="$escaped"]');
                })()"""
            case SelectorNode.FirstOf(selectors) =>
                val attempts = selectors.toSeq.map(resolveElementJsScoped(_, rootExpr)).mkString(" || ")
                s"($attempts)"
            case SelectorNode.Within(parent, child) =>
                val parentJs = resolveElementJsScoped(parent, rootExpr)
                resolveElementJsScoped(child, parentJs)
            case SelectorNode.Visible(inner) =>
                // Resolve via `inner` against the same scope, then apply the shared ARIA visibility filter. Returns the first match that
                // passes, otherwise null. Uses `resolveAllElementsJsScoped` so the filter sees the whole candidate set; `find` returns the
                // first visible candidate, not the first attached one.
                val allJs = resolveAllElementsJsScoped(inner, rootExpr)
                s"""(() => {
                    $ariaVisibleFn
                    const _all = $allJs;
                    for (const el of _all) {
                        if (_kyoAriaVisible(el)) return el;
                    }
                    return null;
                })()"""
    end resolveElementJsScoped

    /** Returns a JS expression that resolves to an array of all matching elements. */
    private[kyo] def resolveAllElementsJs(node: SelectorNode): String =
        resolveAllElementsJsScoped(node, "document")

    private def resolveAllElementsJsScoped(node: SelectorNode, rootExpr: String): String =
        node match
            case SelectorNode.Css(v) =>
                val sel = JsStringUtil.escapeJsString(v)
                if rootExpr == "document" then s"Array.from(document.querySelectorAll('$sel'))"
                else
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return [];
                        return Array.from(_r.querySelectorAll('$sel'));
                    })()"""
                end if
            case SelectorNode.Id(v) =>
                val cssEsc = escapeCssIdent(v)
                val jsEsc  = JsStringUtil.escapeJsString(cssEsc)
                // Use querySelectorAll('#id'), not getElementById (which returns only the first match). Duplicate ids are illegal per
                // HTML but appear in practice on responsive sites where mobile + desktop layouts duplicate the same id. Returning all
                // matches lets `.visible`, `count`, and `assertCount` see the real cardinality.
                if rootExpr == "document" then
                    s"Array.from(document.querySelectorAll('#$jsEsc'))"
                else
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return [];
                        return Array.from(_r.querySelectorAll('#$jsEsc'));
                    })()"""
                end if
            case SelectorNode.Aria(role, name) =>
                val css = implicitRoleCss(role)
                if name.isEmpty then
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return [];
                        $ariaVisibleFn
                        return Array.from(_r.querySelectorAll('$css')).filter(_kyoAriaVisible);
                    })()"""
                else
                    val escaped = JsStringUtil.escapeJsString(name)
                    s"""(() => {
                        const _r = $rootExpr;
                        if (!_r) return [];
                        $ariaVisibleFn
                        const result = [];
                        const els = _r.querySelectorAll('$css');
                        for (const el of els) {
                            if (!_kyoAriaVisible(el)) continue;
                            const label = el.getAttribute('aria-label') || el.textContent.trim();
                            if (label === '$escaped') result.push(el);
                        }
                        return result;
                    })()"""
                end if
            case SelectorNode.Text(value, exact) =>
                val escaped = JsStringUtil.escapeJsString(value)
                val matchOp = if exact then "=== '" + escaped + "'" else ".includes('" + escaped + "')"
                val trimOp  = if exact then ".trim()" else ""
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return [];
                    const result = [];
                    const walker = document.createTreeWalker(_r, NodeFilter.SHOW_TEXT);
                    while (walker.nextNode()) {
                        const t = walker.currentNode.textContent$trimOp;
                        if (t$matchOp) result.push(walker.currentNode.parentElement);
                    }
                    const inputs = _r.querySelectorAll('input, textarea');
                    for (const el of inputs) {
                        const p = (el.getAttribute('placeholder') || '')$trimOp;
                        if (p$matchOp) result.push(el);
                    }
                    return result;
                })()"""
            case SelectorNode.TestId(v) =>
                val escaped = JsStringUtil.escapeJsString(v)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return [];
                    return Array.from(_r.querySelectorAll('[data-testid="$escaped"]'));
                })()"""
            case SelectorNode.Label(text) =>
                val escaped = JsStringUtil.escapeJsString(text)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return [];
                    const result = [];
                    const labels = _r.querySelectorAll('label');
                    for (const lbl of labels) {
                        if (lbl.textContent.trim() !== '$escaped') continue;
                        const forId = lbl.getAttribute('for');
                        if (forId) {
                            const target = document.getElementById(forId);
                            if (target) { result.push(target); continue; }
                        }
                        const nested = lbl.querySelector('input, textarea, select');
                        if (nested) result.push(nested);
                    }
                    return result;
                })()"""
            case SelectorNode.Placeholder(text) =>
                val escaped = JsStringUtil.escapeJsString(text)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return [];
                    return Array.from(_r.querySelectorAll('input[placeholder="$escaped"], textarea[placeholder="$escaped"]'));
                })()"""
            case SelectorNode.Title(text) =>
                val escaped = JsStringUtil.escapeJsString(text)
                s"""(() => {
                    const _r = $rootExpr;
                    if (!_r) return [];
                    return Array.from(_r.querySelectorAll('[title="$escaped"]'));
                })()"""
            case SelectorNode.FirstOf(selectors) =>
                // `FirstOf` is always constructed with at least two selectors (see `or` smart-constructors above),
                // so reduceLeft is safe.
                val attempts = selectors.toSeq
                    .map(resolveAllElementsJsScoped(_, rootExpr))
                    .reduceLeft((a, b) => s"$a.concat($b)")
                s"($attempts)"
            case SelectorNode.Within(parent, child) =>
                val parentJs = resolveElementJsScoped(parent, rootExpr)
                resolveAllElementsJsScoped(child, parentJs)
            case SelectorNode.Visible(inner) =>
                // Resolve the inner selector to a full candidate list, then filter by the same _kyoAriaVisible predicate ARIA uses.
                val allJs = resolveAllElementsJsScoped(inner, rootExpr)
                s"""(() => {
                    $ariaVisibleFn
                    return ($allJs).filter(_kyoAriaVisible);
                })()"""
    end resolveAllElementsJsScoped

    /** Escapes a string so it is a valid CSS identifier per the CSS Syntax Module (Selectors Level 4).
      *
      * Backslash-escapes every character that is CSS-significant outside of plain `[a-zA-Z0-9_-]` plus `U+0080+`. This covers spaces, `:`,
      * `.`, `#`, `/`, `,`, `>`, `+`, `~`, `=`, `"`, `'`, `(`, `)`, `[`, `]`, and all ASCII punctuation. The result is safe to embed after a
      * `#` (id selector) or `.` (class selector).
      *
      * Leading ASCII digits in an identifier require hex escape (`\\31 ` for `1`); the first-character rule is handled separately.
      */
    private[kyo] def escapeCssIdent(s: String): String =
        @tailrec
        def loop(i: Int, sb: StringBuilder): StringBuilder =
            if i >= s.length then sb
            else
                val c = s.charAt(i)
                val needsEscape =
                    !(c >= 'a' && c <= 'z') &&
                        !(c >= 'A' && c <= 'Z') &&
                        !(c >= '0' && c <= '9') &&
                        c != '_' &&
                        c != '-' &&
                        c < 0x80
                val firstCharDigit = i == 0 && c >= '0' && c <= '9'
                val next =
                    if firstCharDigit then
                        sb.append('\\').append(Integer.toHexString(c.toInt)).append(' ')
                    else if needsEscape then
                        sb.append('\\').append(c)
                    else sb.append(c)
                loop(i + 1, next)
        loop(0, new StringBuilder(s.length * 2)).toString
    end escapeCssIdent

    /** JS function literal for the visibility filter shared by every ARIA semantic-selector resolver (textbox, button, heading, …).
      *
      * Matches the user mental model of "the [Search] textbox" excluding scaffolding: an element counts as visible only if it passes the
      * platform's accessibility-tree visibility check (`Element.checkVisibility`, Chrome 105+, looks at computed `display`, `visibility`,
      * `content-visibility`, and `details > summary` hidden state) AND has no `aria-hidden="true"` on itself or any ancestor. The opacity
      * check is intentionally OFF: fully-transparent but hit-testable controls (loading shimmer overlays, fade-in animations) are still
      * semantically present.
      *
      * Inlined into each Aria-resolver IIFE so the JS template is self-contained; the helper is named `_kyoAriaVisible` to avoid collisions
      * with page globals.
      */
    private val ariaVisibleFn: String =
        """const _kyoAriaVisible = (el) => {
            if (typeof el.checkVisibility === 'function' && !el.checkVisibility({checkVisibilityCSS: true, checkOpacity: false})) return false;
            let cur = el;
            while (cur) {
                if (cur.getAttribute && cur.getAttribute('aria-hidden') === 'true') return false;
                cur = cur.parentElement;
            }
            return true;
        };"""

    /** Implicit-role mappings: ARIA role → list of CSS selectors that match HTML elements with that implicit role.
      *
      * Static lookup; populated once at class init. `implicitRoleCssCache` below memoises the full `[role="<role>"], <implicits>` union
      * string for these well-known roles so each selector resolution is a single `Map.get`.
      */
    private val implicitRoleMappings: Dict[String, Seq[String]] = Dict(
        "button" -> Seq("button", """input[type="button"]""", """input[type="submit"]""", """input[type="reset"]"""),
        "link"   -> Seq("a[href]", "area[href]"),
        "textbox" -> Seq(
            """input[type="text"]""",
            """input[type="email"]""",
            """input[type="url"]""",
            """input[type="tel"]""",
            """input[type="search"]""",
            """input[type="password"]""",
            "input:not([type])",
            "textarea"
        ),
        "checkbox" -> Seq("""input[type="checkbox"]"""),
        "radio"    -> Seq("""input[type="radio"]"""),
        "combobox" -> Seq("select:not([multiple]):not([size])", """select:not([multiple])[size="1"]"""),
        "heading"  -> Seq("h1", "h2", "h3", "h4", "h5", "h6"),
        "img"      -> Seq("img")
    )

    /** Memoised `implicitRoleCss` results for the well-known roles in [[implicitRoleMappings]]. The keys are the role names themselves; for
      * any other role the cache misses and the slow path runs (which is a single small `mkString`).
      */
    private val implicitRoleCssCache: Dict[String, String] =
        implicitRoleMappings.map { (role, implicits) =>
            val escaped = JsStringUtil.escapeJsString(role)
            role -> (s"""[role="$escaped"]""" +: implicits).mkString(", ")
        }

    /** Builds a CSS selector for an ARIA role that matches BOTH the explicit `role="<role>"` attribute and the HTML elements that have
      * `<role>` as their implicit ARIA role per the WHATWG mapping.
      *
      * Returns a comma-separated union suitable for `document.querySelector(All)`. Passed through `escapeJsString` by the caller before it
      * lands inside a JS single-quoted literal.
      */
    private def implicitRoleCss(role: String): String =
        // Fast path: well-known role → memoised string in [[implicitRoleCssCache]]. Falls through to the explicit-only form for
        // arbitrary roles (e.g. ARIA roles without an HTML implicit mapping).
        implicitRoleCssCache.getOrElse(role, s"""[role="${JsStringUtil.escapeJsString(role)}"]""")
    end implicitRoleCss

    // ---- Diagnostic probes ----

    /** Returns a JS expression evaluating to the count of elements that match the selector's role + accessible-name criteria, IGNORING the
      * visibility filter applied by [[resolveElementJs]] / [[resolveAllElementsJs]]. Used by [[kyo.internal.Actionability]] to enrich
      * `NotAttached` errors for ARIA selectors: a non-zero count means the candidates exist but were filtered by visibility (display:none,
      * aria-hidden, etc.), while zero means no element with this role+name exists on the page.
      *
      * Defined for [[SelectorNode.Aria]] only; non-ARIA selectors return `"0"` (no enrichment applies; those selectors don't apply the
      * implicit ARIA visibility filter, so a no-match is unambiguous).
      */
    private[kyo] def unfilteredAriaCountExprJs(node: SelectorNode): String =
        node match
            case SelectorNode.Aria(role, name) =>
                val css = implicitRoleCss(role)
                if name.isEmpty then s"document.querySelectorAll('$css').length"
                else
                    val escaped = JsStringUtil.escapeJsString(name)
                    s"""(() => {
                        const els = document.querySelectorAll('$css');
                        let n = 0;
                        for (const el of els) {
                            const label = el.getAttribute('aria-label') || el.textContent.trim();
                            if (label === '$escaped') n++;
                        }
                        return n;
                    })()"""
                end if
            // `Visible(Aria(...))` is the same unfiltered count as the inner Aria: the wrapper only changes the visibility filter,
            // and this probe is by definition unfiltered. Delegating preserves the "candidates exist but were filtered" diagnostic.
            case SelectorNode.Visible(inner) => unfilteredAriaCountExprJs(inner)
            case _                           => "0"

    // ---- Count / read JS expression builders ----

    /** Builds a self-contained JS expression that evaluates to the element-count for `selector`, matching `locateCount`'s semantics
      * exactly, including the `FirstOf` short-circuit (return the first alternative with a non-zero count, else 0). Used as the `valueExpr`
      * fed to `withStability` / [[kyo.internal.StabilitySampler]] so the count can be sampled in-page without a Scala-side round-trip per
      * sample.
      */
    private[kyo] def countExprJs(node: SelectorNode): String =
        node match
            case SelectorNode.FirstOf(selectors) =>
                // Mirror locateCount's short-circuit in JS: evaluate each alternative's count, return the first non-zero, else 0.
                val branches = selectors.toSeq.map(alt => s"(() => { const _n = ${countExprJs(alt)}; return _n; })()")
                val chain    = branches.map(b => s"($b)").mkString(" || ")
                s"($chain || 0)"
            case _ =>
                s"(${SelectorJs.resolveAllElementsJs(node)}).length"

    /** Self-contained JS expression reading the element's text, or the `"not_attached"` sentinel when the selector matches nothing. Shared
      * between `readTextCore` (the Scala-side single read) and the stability sampler (which samples this expression in-page).
      */
    private[kyo] def readTextExprJs(jsExpr: String): String =
        s"""(() => {
            const el = $jsExpr;
            if (!el || !el.isConnected) return 'not_attached';
            return el.innerText || el.textContent || '';
        })()"""

    /** Self-contained JS expression reading the first-matched element's bounding-client-rect for diagnostic enrichment.
      *
      * Returns the string `"w×h"` (e.g. `"0×0"`, `"404×32"`) on success or `"detached"` when the selector matches nothing. Used by
      * `Actionability.enrichDescriptionForReason` to append element geometry to `BrowserElementNotActionableException` messages for
      * `NotVisible` failures, distinguishing "the element has rect 0×0 (display:none collapses it)" from "the element has rect 404×32 but
      * an ancestor is display:none / visibility:hidden".
      */
    private[kyo] def boundingRectSummaryExprJs(node: SelectorNode): String =
        val resolve = SelectorJs.resolveElementJs(node)
        s"""(() => {
            const el = $resolve;
            if (!el || !el.isConnected) return 'detached';
            const r = el.getBoundingClientRect();
            return Math.round(r.width) + 'x' + Math.round(r.height);
        })()"""
    end boundingRectSummaryExprJs

    /** Self-contained JS expression reading the named attribute, or the `"not_attached"` sentinel when the selector matches nothing. */
    private[kyo] def readAttributeExprJs(attrName: String, jsExpr: String): String =
        val escAttr = JsStringUtil.escapeJsString(attrName)
        s"""(() => {
            const el = $jsExpr;
            if (!el || !el.isConnected) return 'not_attached';
            return el.getAttribute('$escAttr') || '';
        })()"""
    end readAttributeExprJs

end SelectorJs
