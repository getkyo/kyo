package kyo.kernel.internal

import kyo.<
import kyo.Frame
import scala.annotation.static

/** The debugger's seam: one installed instance, one no-op default, and nothing else in the kernel.
  *
  * Every method is a semantic identity, so the no-op makes the kernel behave exactly as if this class did
  * not exist. The zero-cost mechanism is class hierarchy analysis, not a flag: real subclasses live in
  * the debug tooling modules, so a production classpath never loads one, the identity defaults stay
  * monomorphic, and every call site devirtualizes and inlines to nothing. Loading and installing a real
  * debugger triggers the one deoptimization and recompile, which is the debug session's cost to pay.
  *
  * `enter` is the strict-path gate, consulted by `Safepoint.enter` on every inline application: observe
  * and route. True proceeds inline; false sends that one application through a `Defer` node into the
  * eval, where the stack-aware hooks see it with the value and the stack in hand. The stack handed to
  * those hooks is readable during the callback only and never retained: it is pooled.
  *
  * The bytecode of the hooked methods does grow, since the calls are emitted unconditionally; the
  * bytecode-shape pins record the sizes, and a compile-time emission gate around each call remains the
  * designed retreat if measurement ever charges for the growth.
  */
abstract private[kyo] class Debugger:

    /** Whether a strict application may run inline; false routes it through the eval, where the
      * stack-aware hooks see it with its frame.
      *
      * Frameless on purpose: two shapes that carried a frame to the strict path were rejected by the
      * board, since even the operand alone taxed every call site. A session that wants frames routes
      * the application and reads them at `onDefer`. Consulted only within an eval's extent: the eval
      * drains its slot at entry while a session is installed, so every strict application there lands
      * on the cold path where this gate lives, and `Safepoint.enter`'s hot path stays byte-identical
      * for everyone else. Strict construction outside any eval runs unobserved, the same carve-out
      * between-slices semantics already draw.
      *
      * Consulted twice for a routed application: at the application site, and again when the eval
      * delivers the settled payload back into the step (the step's own apply carries the gate). A
      * session that refuses must therefore allow the delivery retry, or the step defers against its
      * own refusal forever; the usual shape is a per-thread toggle that refuses, lets the step surface
      * at `onDefer`, and allows the next consult. While a session allows applications, the depth guard
      * does not bound strict recursion; a debugger that runs the program pays the program's shape.
      */
    def enter(): Boolean = true

    /** A step about to run; the returned value replaces the payload. The type parameters keep the eval
      * cast-free: a swapping session asserts conformance inside its own implementation instead.
      */
    def onDefer[A, S](stack: Stack, frame: Frame, value: A < S): A < S = value

    /** An operation performed; observation only. */
    def onSuspend[A](stack: Stack, frame: Frame, input: A): Unit = ()

    /** A value flowing back into the receiving entry; the returned value replaces it. */
    def onDeliver[A](stack: Stack, frame: Frame, value: A): A = value

    /** False routes handler dispatch through the general, eval-visible paths. */
    def fastPathsAllowed: Boolean = true
end Debugger

// Public object, private[kyo] members: see the note on Safepoint for the accessor the other shape emits.
object Debugger:

    // the cell lives in DebuggerPlatformSpecific: `@static` on jvm-native, a plain module var on
    // js-wasm, where a static field initializer runs at script evaluation and could capture
    // undefined for `Noop`. Not volatile on purpose: routing correctness comes from the eval
    // reading the debugger once at entry, not from cross-thread publication timing, and a volatile
    // read inside Safepoint.enter is bytecode every strict call site carries.

    /** Installs the debugger for the extent of a session. An eval reads it once at entry, so evals
      * already running keep what they read and a session boundary never tears mid-eval.
      */
    @static private[kyo] def install(d: Debugger): Unit = DebuggerPlatformSpecific.current = d

    @static private[kyo] def uninstall(): Unit = DebuggerPlatformSpecific.current = Noop

    @static private[kyo] def get: Debugger = DebuggerPlatformSpecific.current

    private[kyo] object Noop extends Debugger

end Debugger
