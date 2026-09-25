package kyo

/** A CSS selector targeting elements for a [[kyo.Stylesheet]] rule.
  *
  * The primary case is a class selector ([[kyo.Selector.cls]]) targeting elements that carry a
  * matching `kyo.UI.cssClass`. `id` and `data-*` selectors are also provided for the existing
  * `UI.id`/`UI.data` hooks. A pseudo-class/element variant (`:hover`, `:focus`, `::before`, ...)
  * is attached with [[kyo.Selector.pseudo]]; a descendant combinator with [[kyo.Selector.descendant]]
  * and a direct-child combinator with [[kyo.Selector.child]]. Two conditions on the SAME element are
  * combined with [[kyo.Selector.and]]. Selectors are immutable values; building one never mutates
  * the receiver.
  *
  * @see
  *   [[kyo.Stylesheet.rule]] for the rule a selector heads
  * @see
  *   `kyo.UI.cssClass` for the element class a class selector matches
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

    /** A compound selector: both parts must match the same element, e.g.
      * `Selector.data("theme", "dark").and(Selector.data("density", "compact"))` yields
      * `[data-theme="dark"][data-density="compact"]`, and `Selector.cls("btn").and(Selector.cls("lg"))`
      * yields `.btn.lg`. Distinct from [[descendant]] and [[child]], which relate two different elements.
      *
      * CSS requires a type selector to come first in a compound, so `Selector.cls("nav").and(Selector.tag("a"))`
      * yields `a.nav`. Both sides are expected to be compounds themselves: a side built with [[descendant]] or
      * [[child]] does not describe one element, and at most one side may name a tag.
      */
    def and(other: Selector): Selector =
        if Selector.startsWithType(other.css) && !Selector.startsWithType(css) then Selector(other.css + css)
        else Selector(css + other.css)

    /** A descendant combinator: `parent.descendant(child)` yields `parent child`. */
    def descendant(child: Selector): Selector = Selector(css + " " + child.css)

    /** A direct-child combinator: `parent.child(c)` yields `parent > c`, matching only `c` elements
      * that are immediate children of `parent` (not deeper descendants). Use this when a descendant
      * combinator would over-reach into a nested subtree that must keep its own styling.
      */
    def child(c: Selector): Selector = Selector(css + " > " + c.css)

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

    private def startsWithType(css: String): Boolean =
        css.nonEmpty && (css.charAt(0).isLetter || css.charAt(0) == '*')
end Selector
