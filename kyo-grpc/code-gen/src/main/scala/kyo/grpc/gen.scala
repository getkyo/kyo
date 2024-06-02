package kyo.grpc

import protocbridge.Artifact
import scalapb.GeneratorOption
import protocbridge.SandboxedJvmGenerator

object gen {
  def apply(
      options: GeneratorOption*
  ): (SandboxedJvmGenerator, Seq[String]) =
    (
      SandboxedJvmGenerator.forModule(
        "scala",
        Artifact(
          kyo.grpc.compiler.BuildInfo.organization,
          "kyo-grpc-codegen_2.12",
          kyo.grpc.compiler.BuildInfo.version
        ),
        "kyo.grpc.compiler.CodeGenerator$",
        kyo.grpc.compiler.CodeGenerator.suggestedDependencies
      ),
      options.map(_.toString)
    )

  def apply(
      options: Set[GeneratorOption] = Set.empty
  ): (SandboxedJvmGenerator, Seq[String]) = apply(options.toSeq: _*)
}