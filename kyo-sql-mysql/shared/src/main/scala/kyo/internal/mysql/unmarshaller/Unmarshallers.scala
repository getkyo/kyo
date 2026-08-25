package kyo.internal.mysql.unmarshaller

/** Aggregates all Backend message unmarshallers for the MySQL wire protocol.
  *
  * Holds one unmarshaller singleton per Backend message type received from the MySQL server. Inject [[Unmarshallers.default]] at
  * construction sites to avoid direct singleton references and simplify testing.
  *
  * Obtain the singleton via [[Unmarshallers.default]]. To substitute a custom unmarshaller (e.g. for testing), construct a new
  * `Unmarshallers` instance with the desired fields.
  */
final class Unmarshallers(
    val handshakeV10: HandshakeV10Unmarshaller.type,
    val okPacket: OkPacketUnmarshaller.type,
    val errPacket: ErrPacketUnmarshaller.type,
    val eofPacket: EofPacketUnmarshaller.type,
    val authSwitchRequest: AuthSwitchRequestUnmarshaller.type,
    val authMoreData: AuthMoreDataUnmarshaller.type,
    val columnDefinition41: ColumnDefinition41Unmarshaller.type,
    val stmtPrepareOk: StmtPrepareOkUnmarshaller.type
)

object Unmarshallers:
    val default: Unmarshallers = new Unmarshallers(
        handshakeV10 = HandshakeV10Unmarshaller,
        okPacket = OkPacketUnmarshaller,
        errPacket = ErrPacketUnmarshaller,
        eofPacket = EofPacketUnmarshaller,
        authSwitchRequest = AuthSwitchRequestUnmarshaller,
        authMoreData = AuthMoreDataUnmarshaller,
        columnDefinition41 = ColumnDefinition41Unmarshaller,
        stmtPrepareOk = StmtPrepareOkUnmarshaller
    )
end Unmarshallers
