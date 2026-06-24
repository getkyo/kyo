package kyo

/** The typed failure on every [[Compiler]] op's `Abort` row, a flat hierarchy in package `kyo`
  * modeled on kyo-http `HttpException`.
  *
  * The two operation super-types categorize the failure by what the caller was doing:
  * [[CompilerInitializationFailure]] (a per-config compiler could not start) and
  * [[CompilerOperationFailure]] (a request against a live compiler failed). The leaf case classes are
  * the specific failures, each carrying typed fields with its message built from them, never a
  * free-form detail string. A caller matches the operation trait to discriminate broadly or a leaf to
  * discriminate precisely.
  *
  * Serializable via the hand-written [[Compiler.AsMessage]] codec in the companion: a `KyoException`
  * carries a `Frame` and so cannot auto-derive a `Schema`. The live `Throwable` cause survives
  * in-process; over the worker IPC wire the codec carries the rendered stack and rebuilds a cause from
  * it (the operation leaf that crosses the wire is [[CompilerExecutionException]]).
  */
sealed abstract class CompilerException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** Operation: starting a per-config compiler (in-process pc or a forked worker JVM). */
sealed trait CompilerInitializationFailure extends CompilerException

/** Operation: running a request against a live compiler. */
sealed trait CompilerOperationFailure extends CompilerException

/** The in-process presentation compiler failed to instantiate for the toolchain version. */
final case class CompilerStartException(scalaVersion: String, cause: Throwable)(using Frame)
    extends CompilerException(s"in-process presentation compiler for Scala $scalaVersion failed to start", cause)
    with CompilerInitializationFailure

/** The forked worker JVM failed to launch or its IPC client to connect. */
final case class CompilerWorkerSpawnException(scalaVersion: String, cause: Throwable)(using Frame)
    extends CompilerException(s"worker JVM for Scala $scalaVersion failed to launch", cause)
    with CompilerInitializationFailure

/** The worker JVM launched but did not pass its readiness probe within the timeout. */
final case class CompilerWorkerReadyException(scalaVersion: String, timeout: Duration)(using Frame)
    extends CompilerException(s"worker JVM for Scala $scalaVersion did not become ready within ${timeout.show}")
    with CompilerInitializationFailure

/** The presentation compiler raised while running the request. */
final case class CompilerExecutionException(cause: Throwable)(using Frame)
    extends CompilerException("presentation compiler raised while running the request", cause)
    with CompilerOperationFailure

/** The worker IPC session broke mid-request. */
final case class CompilerTransportException(cause: String | Throwable)(using Frame)
    extends CompilerException("worker IPC session failed", cause)
    with CompilerOperationFailure

/** The request outran the stuck-timeout and the worker was reclaimed. */
final case class CompilerUnresponsiveException(timeout: Duration)(using Frame)
    extends CompilerException(s"compiler did not respond within ${timeout.show} and was reclaimed")
    with CompilerOperationFailure

/** The compiler pool was closed while the request was in flight. */
final case class CompilerClosedException()(using Frame)
    extends CompilerException("the compiler pool is closed")
    with CompilerOperationFailure

object CompilerException:

    /** Permissive cross-equality so `Response.Failed(error)` (which carries a `CompilerException`) can
      * derive `CanEqual`; the leaves hold a `Throwable`, which has no structural `CanEqual`.
      */
    given CanEqual[CompilerException, CompilerException] = CanEqual.canEqualAny

    /** The wire codec. A `KyoException` carries a `Frame` and cannot auto-derive a `Schema`, so this
      * serializes the leaf tag plus its typed fields (a `Throwable` cause as its rendered stack, a
      * `Duration` as nanos) and rebuilds with `Frame.internal`, the rendered stack becoming the cause.
      */
    given Compiler.AsMessage[CompilerException] =
        summon[Schema[(String, String, String, Long)]].transform[CompilerException] {
            case ("start", scalaVersion, cause, _) => CompilerStartException(scalaVersion, restore(cause))(using Frame.internal)
            case ("spawn", scalaVersion, cause, _) => CompilerWorkerSpawnException(scalaVersion, restore(cause))(using Frame.internal)
            case ("ready", scalaVersion, _, nanos) =>
                CompilerWorkerReadyException(scalaVersion, Duration.fromNanos(nanos))(using Frame.internal)
            case ("exec", _, cause, _)         => CompilerExecutionException(restore(cause))(using Frame.internal)
            case ("transport", _, cause, _)    => CompilerTransportException(restore(cause))(using Frame.internal)
            case ("unresponsive", _, _, nanos) => CompilerUnresponsiveException(Duration.fromNanos(nanos))(using Frame.internal)
            case _                             => CompilerClosedException()(using Frame.internal)
        } {
            case CompilerStartException(scalaVersion, cause)         => ("start", scalaVersion, renderCause(cause), 0L)
            case CompilerWorkerSpawnException(scalaVersion, cause)   => ("spawn", scalaVersion, renderCause(cause), 0L)
            case CompilerWorkerReadyException(scalaVersion, timeout) => ("ready", scalaVersion, "", timeout.toNanos)
            case CompilerExecutionException(cause)                   => ("exec", "", renderCause(cause), 0L)
            case CompilerTransportException(cause)                   => ("transport", "", renderCause(cause), 0L)
            case CompilerUnresponsiveException(timeout)              => ("unresponsive", "", "", timeout.toNanos)
            case CompilerClosedException()                           => ("closed", "", "", 0L)
        }

    private def renderCause(cause: String | Throwable): String = cause match
        case t: Throwable =>
            val writer = new java.io.StringWriter
            t.printStackTrace(new java.io.PrintWriter(writer))
            writer.toString
        case s: String => s

    private def restore(rendered: String): Throwable = new RuntimeException(rendered)
end CompilerException
