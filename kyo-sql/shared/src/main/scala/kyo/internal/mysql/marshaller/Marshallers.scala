package kyo.internal.mysql.marshaller

/** Aggregates all Frontend message marshallers for the MySQL wire protocol.
  *
  * Holds one marshaller singleton per Frontend message type sent to the MySQL server. Inject [[Marshallers.default]] at construction sites
  * to avoid direct singleton references and simplify testing.
  *
  * Obtain the singleton via [[Marshallers.default]]. To substitute a custom marshaller (e.g. for testing), construct a new `Marshallers`
  * instance with the desired fields.
  */
final class Marshallers(
    val handshakeResponse41: HandshakeResponse41Marshaller.type,
    val sslRequest: SslRequestMarshaller.type,
    val authSwitchResponse: AuthSwitchResponseMarshaller.type,
    val authMoreData: AuthMoreDataMarshaller.type,
    val comQuery: ComQueryMarshaller.type,
    val comStmtPrepare: ComStmtPrepareMarshaller.type,
    val comStmtExecute: ComStmtExecuteMarshaller.type,
    val comStmtClose: ComStmtCloseMarshaller.type,
    val comStmtReset: ComStmtResetMarshaller.type,
    val comResetConnection: ComResetConnectionMarshaller.type,
    val comPing: ComPingMarshaller.type,
    val comQuit: ComQuitMarshaller.type
)

object Marshallers:
    val default: Marshallers = new Marshallers(
        handshakeResponse41 = HandshakeResponse41Marshaller,
        sslRequest = SslRequestMarshaller,
        authSwitchResponse = AuthSwitchResponseMarshaller,
        authMoreData = AuthMoreDataMarshaller,
        comQuery = ComQueryMarshaller,
        comStmtPrepare = ComStmtPrepareMarshaller,
        comStmtExecute = ComStmtExecuteMarshaller,
        comStmtClose = ComStmtCloseMarshaller,
        comStmtReset = ComStmtResetMarshaller,
        comResetConnection = ComResetConnectionMarshaller,
        comPing = ComPingMarshaller,
        comQuit = ComQuitMarshaller
    )
end Marshallers
