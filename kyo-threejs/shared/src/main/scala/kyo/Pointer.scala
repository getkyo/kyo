package kyo

/** The raycast / pointer event payload delivered to interaction handlers (`Pointer => Any < Async`).
  *
  * `point` is the world-space hit position, `distance` the ray distance to the hit, `ndc` the
  * normalized device coordinates of the pointer (`-1..1` on each axis), and `buttons` the pressed
  * mouse-button flags at the moment of the event.
  */
final case class Pointer(
    point: Vec3,
    distance: Double,
    ndc: (Double, Double),
    buttons: Pointer.Buttons
) derives CanEqual

object Pointer:
    /** The pressed mouse-button flags at the moment of a pointer event. */
    final case class Buttons(left: Boolean, right: Boolean, middle: Boolean) derives CanEqual

    object Buttons:
        val none: Buttons = Buttons(left = false, right = false, middle = false)
    end Buttons
end Pointer
