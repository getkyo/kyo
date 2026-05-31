package kyo

/** Parameters for the `elicitation/create` reverse-direction request.
  *
  * The server sends this to the client when it needs to collect additional information
  * from the end user. `requestedSchema` is a JSON Schema document describing the
  * expected response structure.
  *
  * @param message         human-readable message shown to the user
  * @param requestedSchema JSON Schema describing the expected response shape
  */
final case class McpElicitationRequest(
    message: String,
    requestedSchema: Json.JsonSchema
) derives Schema, CanEqual
