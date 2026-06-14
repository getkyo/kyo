package kyo

import org.scalajs.dom
import scala.scalajs.js

/** Associates the native `dom.MouseEvent` with the shared [[kyo.UI.MouseEvent]] payload built by
  * [[kyo.UIWindow.onClick]], so the [[kyo.UI.MouseEvent.targetClosest]] extension can walk from the
  * click target to a matching ancestor without a `dom.*` field on the shared, platform-neutral
  * `UI.MouseEvent`.
  *
  * The association lives in a `js.WrappedMap` (backed by a native `js.Map`) keyed by the
  * `UI.MouseEvent` instance, with an explicit per-click lifecycle: [[kyo.UIWindow.onClick]] calls
  * `remember` before running the user handler and `forget` via `Sync.ensure` after the handler
  * effect completes (on success, failure, and interrupt). The table holds one live entry per
  * in-flight click handler: each entry is keyed by reference identity on its own distinct
  * `UI.MouseEvent` instance, and it is removed when that handler's effect finishes. Concurrent
  * suspended handlers each hold their own entry and none collides with another, so the table is
  * bounded by the number of concurrently in-flight handlers and never leaks.
  */
private[kyo] object UIMouseEventOps:

    private val table = new js.WrappedMap(js.Map.empty[UI.MouseEvent, dom.MouseEvent])

    private[kyo] def remember(m: UI.MouseEvent, native: dom.MouseEvent): Unit =
        table.update(m, native)

    private[kyo] def forget(m: UI.MouseEvent): Unit =
        table -= m
        ()

    private[kyo] def nativeOf(m: UI.MouseEvent): Maybe[dom.MouseEvent] =
        Maybe(table.getOrElse(m, null))

end UIMouseEventOps

/** A narrow typed handle over a DOM element matched outside the reactive tree (the elements injected
  * by [[kyo.UI.rawHtml]], which carry no `data-kyo-path`). It exposes only what a document-level
  * click handler needs: walk to an ancestor or descendant, read/write attributes, read text. The
  * opaque-type boundary lives here, keeping the raw `dom.Element` out of public signatures without
  * any runtime cast.
  *
  * Built by [[kyo.UI.MouseEvent.targetClosest]]; a top-level js-wasm type paralleling
  * [[kyo.UIWindow]] and [[kyo.UILocation]].
  */
opaque type ElementRef = dom.Element

object ElementRef:
    private[kyo] def apply(e: dom.Element): ElementRef = e

extension (r: ElementRef)
    /** The nearest ancestor (or self) matching `selector`, `Absent` when none matches. */
    def closest(selector: String): Maybe[ElementRef] =
        Maybe(r.closest(selector)).map(ElementRef(_))

    /** The first descendant matching `selector`, `Absent` when none matches. */
    def querySelector(selector: String): Maybe[ElementRef] =
        Maybe(r.querySelector(selector)).map(ElementRef(_))

    /** The value of attribute `name`, `Absent` when the attribute is not present. */
    def getAttribute(name: String): Maybe[String] = Maybe(r.getAttribute(name))

    /** Set attribute `name` to `value`. */
    def setAttribute(name: String, value: String): Unit = r.setAttribute(name, value)

    /** Remove attribute `name`. */
    def removeAttribute(name: String): Unit = r.removeAttribute(name)

    /** The element's text content. */
    def textContent: String = r.textContent
end extension

extension (e: UI.MouseEvent)
    /** The nearest ancestor (or self) of the click target matching `selector`, `Absent` when no
      * ancestor matches or the event was not produced by [[kyo.UIWindow.onClick]].
      */
    def targetClosest(selector: String): Maybe[kyo.ElementRef] =
        UIMouseEventOps.nativeOf(e).flatMap { native =>
            native.target match
                case el: dom.Element => Maybe(el.closest(selector)).map(ElementRef(_))
                case _               => Absent
        }
end extension
