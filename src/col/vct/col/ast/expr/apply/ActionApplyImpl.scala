package vct.col.ast.expr.apply

import vct.col.ast.{ActionApply, TProcess, Type}
import vct.col.print.{Ctx, Doc, Precedence, Text, Group}

trait ActionApplyImpl[G] { this: ActionApply[G] =>
  override def t: Type[G] = TProcess()

  override def precedence: Int = Precedence.POSTFIX
  override def layout(implicit ctx: Ctx): Doc =
    Group(Text(ctx.name(action)) <> "(" <> Doc.args(args) <> ")")
}