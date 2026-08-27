package sandbox

import kyo.Tag

/** The oracle for "same tag": the encoded string itself, read through the erasure so it bypasses
  * fastPathEqual, TagHash and the subtype cache.
  */
def encoding[A](t: Tag[A]): String =
    t.asInstanceOf[Any] match
        case s: String => s
        case d         => "DYNAMIC:" + d.toString
