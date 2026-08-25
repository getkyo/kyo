package kyo.internal.postgres.unmarshaller

/** Aggregates all Backend message unmarshallers for the PostgreSQL wire protocol.
  *
  * Holds one unmarshaller singleton per Backend message type received from the server. Inject [[Unmarshallers.default]] at construction
  * sites to avoid direct singleton references and simplify testing.
  *
  * Obtain the singleton via [[Unmarshallers.default]]. To substitute a custom unmarshaller (e.g. for testing), construct a new
  * `Unmarshallers` instance with the desired fields.
  *
  * @see
  *   [[kyo.internal.postgres.PostgresChannel]] for the consumer of this aggregator.
  */
final class Unmarshallers(
    val authentication: AuthenticationUnmarshaller.type,
    val parameterStatus: ParameterStatusUnmarshaller.type,
    val backendKeyData: BackendKeyDataUnmarshaller.type,
    val readyForQuery: ReadyForQueryUnmarshaller.type,
    val rowDescription: RowDescriptionUnmarshaller.type,
    val dataRow: DataRowUnmarshaller.type,
    val commandComplete: CommandCompleteUnmarshaller.type,
    val errorResponse: ErrorResponseUnmarshaller.type,
    val noticeResponse: NoticeResponseUnmarshaller.type,
    val notificationResponse: NotificationResponseUnmarshaller.type,
    val parseComplete: ParseCompleteUnmarshaller.type,
    val bindComplete: BindCompleteUnmarshaller.type,
    val closeComplete: CloseCompleteUnmarshaller.type,
    val parameterDescription: ParameterDescriptionUnmarshaller.type,
    val noData: NoDataUnmarshaller.type,
    val portalSuspended: PortalSuspendedUnmarshaller.type,
    val copyInResponse: CopyInResponseUnmarshaller.type,
    val copyOutResponse: CopyOutResponseUnmarshaller.type,
    val copyData: CopyDataUnmarshaller.type,
    val copyDone: CopyDoneUnmarshaller.type
)

object Unmarshallers:
    val default: Unmarshallers = new Unmarshallers(
        authentication = AuthenticationUnmarshaller,
        parameterStatus = ParameterStatusUnmarshaller,
        backendKeyData = BackendKeyDataUnmarshaller,
        readyForQuery = ReadyForQueryUnmarshaller,
        rowDescription = RowDescriptionUnmarshaller,
        dataRow = DataRowUnmarshaller,
        commandComplete = CommandCompleteUnmarshaller,
        errorResponse = ErrorResponseUnmarshaller,
        noticeResponse = NoticeResponseUnmarshaller,
        notificationResponse = NotificationResponseUnmarshaller,
        parseComplete = ParseCompleteUnmarshaller,
        bindComplete = BindCompleteUnmarshaller,
        closeComplete = CloseCompleteUnmarshaller,
        parameterDescription = ParameterDescriptionUnmarshaller,
        noData = NoDataUnmarshaller,
        portalSuspended = PortalSuspendedUnmarshaller,
        copyInResponse = CopyInResponseUnmarshaller,
        copyOutResponse = CopyOutResponseUnmarshaller,
        copyData = CopyDataUnmarshaller,
        copyDone = CopyDoneUnmarshaller
    )
end Unmarshallers
