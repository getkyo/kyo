package kyo.lang.plugin

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools.dotc.report

/** Rejects `kyo.lang.Checked` declared by hand in a `.scala` source.
  *
  * `Checked` attests that a definition went through `.kyo` validation and lowering. [[Preparation]] attaches it to every `.kyo`
  * definition, and validation of a later `.kyo` compilation admits any definition carrying it. A `.scala` source compiled with the
  * plugin that declares the annotation itself would therefore smuggle unchecked code into the admitted surface, so this post-typer phase
  * turns that declaration into a compile error.
  */
final class CheckMetadata extends PluginPhase:
    val phaseName: String = "kyoLangMetadata"

    override val runsAfter: Set[String] = Set("typer")

    private def check(tree: tpd.MemberDef)(using Context): Unit =
        if !Preparation.isKyoUnit && tree.symbol.annotations.exists(_.symbol.fullName.toString == Preparation.checkedClass) then
            report.error("Checked metadata is generated for .kyo definitions; it cannot be declared in .scala", tree.srcPos)

    override def transformDefDef(tree: tpd.DefDef)(using Context): tpd.Tree =
        check(tree)
        tree

    override def transformValDef(tree: tpd.ValDef)(using Context): tpd.Tree =
        check(tree)
        tree

    override def transformTypeDef(tree: tpd.TypeDef)(using Context): tpd.Tree =
        check(tree)
        tree
end CheckMetadata
