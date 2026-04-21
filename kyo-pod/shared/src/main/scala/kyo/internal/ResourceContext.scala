package kyo.internal

import kyo.*

/** Identifies the resource being operated on for error classification.
  *
  * Used by shell and HTTP backends to produce correctly-typed errors without inferring the target from CLI args or operation names.
  */
sealed private[kyo] trait ResourceContext:
    /** A human-readable descriptor used only for diagnostic General messages. */
    def describe: String

private[kyo] object ResourceContext:

    case class Container(id: kyo.Container.Id) extends ResourceContext:
        def describe: String = s"container ${id.value}"

    case class Image(ref: String) extends ResourceContext:
        def describe: String = s"image $ref"

    case class Network(id: kyo.Container.Network.Id) extends ResourceContext:
        def describe: String = s"network ${id.value}"

    case class Volume(id: kyo.Container.Volume.Id) extends ResourceContext:
        def describe: String = s"volume ${id.value}"

    case class Op(name: String) extends ResourceContext:
        def describe: String = s"operation $name"

end ResourceContext
