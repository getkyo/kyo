package kyo.internal

import kyo.*

private[kyo] trait YamlVisitor[Ctx, Err, A]:
    def streamStart(context: Ctx, mark: Yaml.Mark): Result[Err, Ctx]
    def documentStart(context: Ctx, mark: Yaml.Mark): Result[Err, Ctx]
    def mappingStart(context: Ctx, meta: Yaml.Meta): Result[Err, Ctx]
    def sequenceStart(context: Ctx, meta: Yaml.Meta): Result[Err, Ctx]
    def scalar(context: Ctx, value: String, meta: Yaml.ScalarMeta): Result[Err, Ctx]
    def alias(context: Ctx, name: Yaml.Anchor, mark: Yaml.Mark): Result[Err, Ctx]
    def nodeEnd(context: Ctx, mark: Yaml.Mark): Result[Err, Ctx]
    def documentEnd(context: Ctx, mark: Yaml.Mark): Result[Err, Ctx]
    def streamEnd(context: Ctx, mark: Yaml.Mark): Result[Err, A]
end YamlVisitor
