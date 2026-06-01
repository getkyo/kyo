package kyo

/** A CSS selector targeting elements for a [[kyo.Stylesheet]] rule.
  *
  * The primary case is a class selector ([[kyo.Selector.cls]]) targeting elements that carry a
  * matching [[kyo.UI.cssClass]]. `id` and `data-*` selectors are also provided for the existing
  * `UI.id`/`UI.data` hooks. A pseudo-class/element variant (`:hover`, `:focus`, `::before`, ...)
  * is attached with [[kyo.Selector.pseudo]]; a descendant combinator with [[kyo.Selector.descendant]].
  * Selectors are immutable values; building one never mutates the receiver.
  *
  * @see
  *   [[kyo.Stylesheet.rule]] for the rule a selector heads
  * @see
  *   [[kyo.UI.cssClass]] for the element class a class selector matches
  */
final case class Selector private[kyo] (css: String) derives CanEqual:

    /** A pseudo-class or pseudo-element variant of this selector, e.g. `Selector.cls("btn").pseudo("hover")`
      * yields `.btn:hover`. Pass the suffix WITHOUT the leading colon for a pseudo-class; use
      * [[pseudoElement]] for `::` pseudo-elements.
      */
    def pseudo(name: String): Selector = Selector(css + ":" + name)

    /** A pseudo-element variant of this selector using the `::` prefix, e.g. `Selector.cls("btn").pseudoElement("before")`
      * yields `.btn::before`.
      */
    def pseudoElement(name: String): Selector = Selector(css + "::" + name)

    /** A descendant combinator: `parent.descendant(child)` yields `parent child`. */
    def descendant(child: Selector): Selector = Selector(css + " " + child.css)

end Selector

object Selector:
    /** A class selector: `Selector.cls("feat-grid")` is `.feat-grid`. */
    def cls(name: String): Selector = Selector("." + name)

    /** An id selector: `Selector.id("hero")` is `#hero`. */
    def id(name: String): Selector = Selector("#" + name)

    /** An attribute selector on a `data-*` attribute: `Selector.data("active", "true")` is
      * `[data-active="true"]`; omit `value` for a presence selector `[data-active]`.
      */
    def data(name: String, value: String): Selector = Selector(s"""[data-$name="$value"]""")
    def data(name: String): Selector                = Selector(s"[data-$name]")

    /** A raw element/tag selector for the rare case a rule must hit a bare tag (e.g. `body`). */
    def tag(name: String): Selector = Selector(name)
end Selector
