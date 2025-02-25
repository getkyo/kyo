package kyo.test.laws

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * `ZLawful[Caps, R]` describes a capability that is expected to satisfy a set
 * of laws. Lawful instances can be combined using `+` to describe a set of
 * capabilities and all of the laws that those capabilities are expected to
 * satisfy.
 *
 * {{{
 * trait Equal[-A] {
 *   def equal(a1: A, a2: A): Boolean
 * }
 *
 * object Equal extends Lawful[Equal] {
 *   val laws = ???
 * }
 * }}}
 */
trait ZLawful[-Caps[_], -R] { self =>
  def laws: ZLaws[Caps, R]

  def +[Caps1[x] <: Caps[x], R1 <: R](that: ZLawful[Caps1, R1]): ZLawful[Caps1, R1] =
    new ZLawful[Caps1, R1] {
      val laws = self.laws + that.laws
    }
}
