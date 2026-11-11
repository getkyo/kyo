package kyo.internal

import kyo.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** One local tree's ownership of a DOM container. */
final private[kyo] class DomMount private (val container: dom.Element):

    private var active = true

    def isCurrent(using AllowUnsafe): Boolean = active && DomMount.states.get(container).contains(this)

    def owns(target: dom.EventTarget)(using AllowUnsafe): Boolean =
        isCurrent && DomMount.owns(container, target, includeContainer = false)

    private def close(using Frame): Unit < Sync = Sync.defer { active = false }

end DomMount

private[kyo] object DomMount:

    @js.native
    @JSGlobal("WeakMap")
    private class MountStates extends js.Object:
        def get(element: dom.Element): js.UndefOr[DomMount]         = js.native
        def set(element: dom.Element, mount: DomMount): MountStates = js.native
    end MountStates

    // Closed roots remain boundaries while their DOM exists. Weak keys do not retain detached containers.
    private lazy val states = new MountStates

    def init(container: dom.Element, html: String)(using Frame): DomMount < (Sync & Scope) =
        Scope.acquireRelease {
            Sync.defer {
                val mount = new DomMount(container)
                // Replacement and identity publication share a synchronous turn, so a new tree supersedes the old one atomically.
                container.innerHTML = html
                discard(states.set(container, mount))
                mount
            }
        }(_.close)

    def owns(container: dom.Element, target: dom.EventTarget, includeContainer: Boolean = true)(using AllowUnsafe): Boolean =
        target match
            case node: dom.Node =>
                if node eq container then return includeContainer
                var current = node.parentNode
                while current != null do
                    if current eq container then return true
                    current match
                        case element: dom.Element if states.get(element).isDefined => return false
                        case _                                                     => current = current.parentNode
                end while
                false
            case _ => false
    end owns

end DomMount
