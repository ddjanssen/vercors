package vct.col.ast.expr.op.collection

import vct.col.ast.{Size, TInt, Type}
import vct.col.print.{Ctx, Doc, Precedence, Text}

trait SizeImpl[G] { this: Size[G] =>
  override def t: Type[G] = TInt()

  override def precedence: Int = Precedence.ATOMIC
  override def layout(implicit ctx: Ctx): Doc = Text("|") <> obj <> "|"
}