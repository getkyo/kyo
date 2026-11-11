package kyo.internal

import kyo.*
import org.scalajs.dom
import scala.collection.mutable
import scala.scalajs.js

/** Pending native SVG animation starts owned by one local mount. */
final private[kyo] class DomAnimations private ():

    private val pending = mutable.HashSet.empty[Int]
    private var open    = true

    /** Start inserted SMIL elements relative to their insertion, after the browser has registered them.
      * Chart transitions use `begin="indefinite"` to avoid starting against the shared document timeline.
      */
    def begin(root: dom.Element)(using AllowUnsafe): Unit =
        if open then
            val animations = root.querySelectorAll("animate,animateTransform,animateMotion")
            if animations.length > 0 then
                var frame = 0
                // Unsafe: the browser invokes this callback on the JS event loop. It cannot run synchronously before
                // requestAnimationFrame returns its handle, and close cancels it before this mount's scope drains.
                frame = dom.window.requestAnimationFrame { (_: Double) =>
                    discard(pending.remove(frame))
                    if open then
                        var i = 0
                        while i < animations.length do
                            beginElement(animations(i))
                            i += 1
                    end if
                }
                discard(pending.add(frame))
            end if
        end if
    end begin

    def close(using Frame): Unit < Sync = Sync.Unsafe.defer {
        open = false
        pending.foreach(frame => dom.window.cancelAnimationFrame(frame))
        pending.clear()
    }

    private def beginElement(element: dom.Element)(using AllowUnsafe): Unit =
        // Element's facade omits SMIL's timing methods. The selector above restricts this bridge to animation elements.
        // A node replaced before this frame may already be detached, in which case the native call can throw.
        try discard(element.asInstanceOf[js.Dynamic].beginElement())
        catch case _: Throwable => ()

end DomAnimations

private[kyo] object DomAnimations:

    def init(using Frame): DomAnimations < (Scope & Sync) =
        Scope.acquireRelease(Sync.defer(new DomAnimations()))(_.close)

end DomAnimations
