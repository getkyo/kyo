package kyo

/** Result of the `sampling/createMessage` reverse-direction request.
  *
  * @param role       the role of the sampled content
  * @param content    the generated content
  * @param model      the model identifier that produced the response
  * @param stopReason the reason generation stopped, if known
  */
final case class McpSamplingResponse(
    role: McpRole,
    content: McpContent,
    model: String,
    stopReason: Maybe[String] = Absent
) derives Schema, CanEqual
