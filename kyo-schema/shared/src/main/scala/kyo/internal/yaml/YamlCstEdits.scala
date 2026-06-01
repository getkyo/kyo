package kyo.internal.yaml

import kyo.*

private[kyo] object YamlCstEdits:

    private val message = "CST edit operation is not implemented yet"

    def replace(document: Yaml.Cst.Document, path: Yaml.Cst.Path, node: Yaml.Cst.Node): Result[Yaml.Cst.Error, Yaml.Cst.Document] =
        Result.fail(Yaml.Cst.EditException(message, path, Absent))

    def insert(document: Yaml.Cst.Document, path: Yaml.Cst.Path, node: Yaml.Cst.Node): Result[Yaml.Cst.Error, Yaml.Cst.Document] =
        Result.fail(Yaml.Cst.EditException(message, path, Absent))

    def remove(document: Yaml.Cst.Document, path: Yaml.Cst.Path): Result[Yaml.Cst.Error, Yaml.Cst.Document] =
        Result.fail(Yaml.Cst.EditException(message, path, Absent))
end YamlCstEdits
