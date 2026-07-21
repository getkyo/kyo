package kyo.internal.postgres.marshaller

/** Aggregates all Frontend message marshallers for the PostgreSQL wire protocol.
  *
  * Holds one marshaller singleton per Frontend message type. Inject [[Marshallers.default]] at construction sites to avoid direct singleton
  * references and simplify testing.
  *
  * Obtain the singleton via [[Marshallers.default]]. To substitute a custom marshaller (e.g. for testing), construct a new `Marshallers`
  * instance with the desired fields.
  *
  * @see
  *   [[kyo.internal.postgres.Marshaller]] for the base trait.
  */
final class Marshallers(
    val startupMessage: StartupMessageMarshaller.type,
    val sslRequest: SSLRequestMarshaller.type,
    val cancelRequest: CancelRequestMarshaller.type,
    val passwordMessage: PasswordMessageMarshaller.type,
    val saslInitialResponse: SASLInitialResponseMarshaller.type,
    val saslResponse: SASLResponseMarshaller.type,
    val query: QueryMarshaller.type,
    val parse: ParseMarshaller.type,
    val bind: BindMarshaller.type,
    val describe: DescribeMarshaller.type,
    val execute: ExecuteMarshaller.type,
    val sync: SyncMarshaller.type,
    val flush: FlushMarshaller.type,
    val close: CloseMarshaller.type,
    val terminate: TerminateMarshaller.type,
    val copyData: CopyDataMarshaller.type,
    val copyDone: CopyDoneMarshaller.type,
    val copyFail: CopyFailMarshaller.type
)

object Marshallers:
    val default: Marshallers = new Marshallers(
        startupMessage = StartupMessageMarshaller,
        sslRequest = SSLRequestMarshaller,
        cancelRequest = CancelRequestMarshaller,
        passwordMessage = PasswordMessageMarshaller,
        saslInitialResponse = SASLInitialResponseMarshaller,
        saslResponse = SASLResponseMarshaller,
        query = QueryMarshaller,
        parse = ParseMarshaller,
        bind = BindMarshaller,
        describe = DescribeMarshaller,
        execute = ExecuteMarshaller,
        sync = SyncMarshaller,
        flush = FlushMarshaller,
        close = CloseMarshaller,
        terminate = TerminateMarshaller,
        copyData = CopyDataMarshaller,
        copyDone = CopyDoneMarshaller,
        copyFail = CopyFailMarshaller
    )
end Marshallers
