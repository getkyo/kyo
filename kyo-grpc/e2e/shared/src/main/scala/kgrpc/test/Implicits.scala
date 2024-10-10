package kgrpc.test

// Workaround for https://github.com/scalapb/ScalaPB/issues/1705.

given CanEqual[RequestMessage.SealedValue.Empty.type, RequestMessage.SealedValue] = CanEqual.derived
given CanEqual[Request.Empty.type, Request]                                       = CanEqual.derived

given emptyCanEqualResponse: CanEqual[ResponseMessage.SealedValue.Empty.type, ResponseMessage.SealedValue] = CanEqual.derived
given CanEqual[Response.Empty.type, Response]                                                              = CanEqual.derived
