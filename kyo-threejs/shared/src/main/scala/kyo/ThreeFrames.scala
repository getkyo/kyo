package kyo

/** The pluggable frame-source feeding the animation loop, a sealed union so the loop source is
  * exhaustive and a later kyo-webxr round adds a `ThreeFrames.Xr` variant.
  *
  * [[ThreeFrames.Raf]] drives the loop from the browser `requestAnimationFrame` (the default for a
  * live mount). [[ThreeFrames.Clock]] uses a fixed interval via `Clock.repeatAtInterval`, so a step
  * is deterministic under `Clock.withTimeControl`. [[ThreeFrames.Manual]] hands a test a
  * [[Three.Driver]] whose `step` advances exactly one tick, so an animation test asserts the scene
  * mutated without any sleep.
  */
sealed trait ThreeFrames derives CanEqual

object ThreeFrames:
    /** Browser `requestAnimationFrame`-driven loop (the live default). */
    case object Raf extends ThreeFrames

    /** Fixed-interval loop driven by `Clock.repeatAtInterval(interval)`. */
    final case class Clock(interval: Duration) extends ThreeFrames

    /** Deterministic test stepping: `withDriver` receives a [[Three.Driver]] to advance frames by
      * hand.
      */
    final case class Manual(withDriver: Three.Driver => Any < Async) extends ThreeFrames
end ThreeFrames
