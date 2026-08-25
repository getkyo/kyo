package kyo.internal

import kyo.*
import kyo.UI.Ast.*
import scala.annotation.tailrec

/** Which nodes a reactive region's update could be addressed to.
  *
  * A region's update was always the whole region: it re-rendered its subtree and shipped every byte of it,
  * because the diff granularity was the reactive boundary itself. For a chart that is one boundary over the
  * entire lowered SVG, so a tick whose information content is a handful of numbers re-transmitted the
  * background, the axes, the gridlines, every tick label and the legend along with the marks that actually
  * moved, once a second, per connected viewer.
  *
  * Every element renders with its own `data-kyo-path`, and the client resolves a `Replace` by that path, so a
  * nested element is addressable exactly the way the boundary is. This decides which nodes CAN be addressed;
  * what actually goes on the wire is decided downstream, by comparing each candidate's rendered HTML against
  * what was last sent for that path.
  *
  * That split is not an implementation detail, it is the correctness boundary. A node's rendering is NOT a
  * function of its AST alone: an element bound to a `SignalRef` re-renders from the ref read afresh at render
  * time, and its AST is the same object on every edit (see `ReactiveUI.normalize`). Deciding "unchanged" from
  * the AST therefore silently drops real updates, which is why the decision is made on rendered bytes and this
  * file only proposes candidates.
  *
  * The walk descends only where the node shape is uniform enough to compare exactly, which is the SVG subtree:
  * every SVG element is `(svgAttrs, attrs, children)`, so "same node, different children" is a precise
  * question there. It stops at a `foreignObject`, which bridges back to HTML where a user-editable field can
  * live, at a child that renders no node of its own, and at a nested reactive region that owns its own
  * subscription. Anywhere it cannot descend, the enclosing node is the single candidate, which is what every
  * update was before.
  */
private[kyo] object UIDiff:

    /** The (path, subtree) nodes an update to this region could be addressed to.
      *
      * A first render, and a region whose node cannot be decomposed, is the whole region at its own path.
      * Otherwise it is the region's addressable descendants, each at its own path.
      */
    def plan(path: Seq[String], previous: Maybe[UI], current: UI): Chunk[(Seq[String], UI)] =
        previous match
            case Absent => Chunk((path, current))
            case Present(p) =>
                decompose(path, p, current).getOrElse(Chunk((path, current)))

    /** `Present(candidates)` when the two nodes are the same element and their children can each be addressed
      * on their own; `Absent` when the node cannot be decomposed and is the candidate itself.
      */
    private def decompose(path: Seq[String], a: UI, b: UI): Maybe[Chunk[(Seq[String], UI)]] =
        (a, b) match
            case (ea: Svg.SvgElement, eb: Svg.SvgElement) if sameShell(ea, eb) =>
                @tailrec def loop(i: Int, acc: Chunk[(Seq[String], UI)]): Maybe[Chunk[(Seq[String], UI)]] =
                    if i >= ea.children.size then Present(acc)
                    else
                        val cb = eb.children(i)
                        if !addressable(cb) then Absent
                        else
                            val childPath = path :+ i.toString
                            decompose(childPath, ea.children(i), cb) match
                                case Present(sub) => loop(i + 1, acc.concat(sub))
                                case Absent       => loop(i + 1, acc.append((childPath, cb)))
                        end if
                loop(0, Chunk.empty)
            case _ => Absent

    /** Whether the two nodes are the same element differing only in their children.
      *
      * `frame` is a `using` parameter on every SVG case class, so it sits outside the generated `equals` and a
      * node built at a different call site still compares by its content. This comparison is over attributes,
      * which are plain values; the children are what the caller goes on to address separately.
      */
    private def sameShell(a: Svg.SvgElement, b: Svg.SvgElement): Boolean =
        // `equals` rather than `==`: neither SvgAttrs nor Attrs declares a CanEqual instance, and the
        // comparison wanted here is exactly the structural one their generated equals performs.
        a.children.nonEmpty &&
            a.children.size == b.children.size &&
            a.getClass.getName == b.getClass.getName &&
            !opaqueContent(a) &&
            a.svgAttrs.equals(b.svgAttrs) &&
            a.attrs.equals(b.attrs)

    /** Nodes never descended into.
      *
      * `title` and `desc` render a text field of their own alongside their children, so their content is not
      * described by (attributes, children). `foreignObject` bridges back to HTML, where a bound input can
      * live: its rendering reads state no comparison at this level can see.
      */
    private def opaqueContent(a: Svg.SvgElement): Boolean =
        a match
            case _: Svg.Title | _: Svg.Desc | _: Svg.ForeignObject => true
            case _                                                 => false

    /** Whether a child can be replaced on its own.
      *
      * Only an element renders a tag carrying `data-kyo-path`, which is what the client resolves a `Replace`
      * against; a fragment, a text node or raw html has no node of its own to address. A nested reactive
      * region is an element in the rendered output but owns its own subscription, so replacing it from the
      * enclosing region would clobber a subtree that updates itself.
      */
    private def addressable(ui: UI): Boolean =
        ui match
            case _: Element => true
            case _          => false

end UIDiff
