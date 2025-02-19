package vct.col.ast.expr.bip

import vct.col.ast.{BipLocalIncomingData, Type}
import vct.col.print.{Ctx, Doc, Text}

trait BipLocalIncomingDataImpl[G] { this: BipLocalIncomingData[G] =>
  override def t: Type[G] = ref.decl.t

  override def layout(implicit ctx: Ctx): Doc = Text(ctx.name(ref))
}
