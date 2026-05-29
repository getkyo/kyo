package kyo.internal

import kyo.*
import kyo.Maybe
import kyo.Schema
import kyo.Structure

private[kyo] case class JsonRpcRequest(
    id: Maybe[JsonRpcId],
    method: String,
    params: Maybe[Structure.Value]
) derives Schema, CanEqual
