package caliban

import caliban.Configurator.ExecutionConfiguration
import caliban.ResponseValue.ObjectValue
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

/** Bridge to caliban's package-private HttpUtils. */
object CalibanHttpUtils:

    def graphiqlHtml(graphqlPath: String): String =
        HttpUtils.graphiqlHtml(graphqlPath, graphqlPath, None)

    def acceptsGqlJson(acceptHeader: Option[String]): Boolean =
        HttpUtils.AcceptsGqlEncodings(acceptHeader).graphQLJson

    def computeCacheDirective(extensions: ObjectValue): Option[String] =
        HttpUtils.computeCacheDirective(extensions)

    def isMutationOverGetError(errors: List[CalibanError]): Boolean =
        errors.contains(HttpUtils.MutationOverGetError)

    val responseValueCodec: JsonValueCodec[ResponseValue] =
        ResponseValue.jsoniterCodec

    val inputValueCodec: JsonValueCodec[InputValue] =
        InputValue.jsoniterCodec

    def transformSseResponse[Sse](
        response: GraphQLResponse[?],
        toSse: ResponseValue => Sse,
        completeSse: Sse
    ): zio.stream.ZStream[Any, Nothing, Sse] =
        HttpUtils.ServerSentEvents.transformResponse(response, toSse, completeSse)

    object DeferMultipart:
        val innerBoundary: String             = HttpUtils.DeferMultipart.InnerBoundary
        val endBoundary: String               = HttpUtils.DeferMultipart.EndBoundary
        val headerParams: Map[String, String] = HttpUtils.DeferMultipart.DeferHeaderParams

        def createPipeline[E](response: GraphQLResponse[E]): zio.stream.ZPipeline[Any, Throwable, ResponseValue, ResponseValue] =
            HttpUtils.DeferMultipart.createPipeline(response)
    end DeferMultipart

    def configuredInterpreter[R](
        interpreter: GraphQLInterpreter[R, CalibanError],
        config: ExecutionConfiguration
    ): GraphQLInterpreter[R, CalibanError] =
        interpreter.wrapExecutionWith(Configurator.ref.locally(config)(_))

end CalibanHttpUtils
