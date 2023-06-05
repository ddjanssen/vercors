package vct.col.rewrite.lang

import com.typesafe.scalalogging.LazyLogging
import vct.col.ast._
import vct.col.origin.{BlameCollector, LLVMOrigin, Origin}
import vct.col.rewrite.{Generation, Rewritten}
import vct.col.origin.RedirectOrigin.StringReadable
import vct.col.ref.{LazyRef, Ref}
import vct.col.resolve.ctx.RefLlvmFunctionDefinition
import vct.col.util.SuccessionMap

case class LangLLVMToCol[Pre <: Generation](rw: LangSpecificToCol[Pre]) extends LazyLogging {
  type Post = Rewritten[Pre]
  implicit val implicitRewriter: AbstractRewriter[Pre, Post] = rw

  private val functionMap: SuccessionMap[LlvmFunctionDefinition[Pre], Procedure[Post]] = SuccessionMap()


  def rewriteFunctionDef(func: LlvmFunctionDefinition[Pre]): Unit = {
    implicit val o: Origin = func.contract.o
    val procedure = rw.labelDecls.scope {
      rw.globalDeclarations.declare(
        new Procedure[Post](
          returnType = rw.dispatch(func.returnType),
          args = rw.variables.collect {
            func.args.foreach(rw.dispatch)
          }._1,
          outArgs = Nil,
          typeArgs = Nil,
          body = Some(rw.dispatch(func.functionBody)),
          contract = rw.dispatch(func.contract.data.get)
        )(func.blame)
      )
    }
    functionMap.update(func, procedure)
  }

  def result(ref: RefLlvmFunctionDefinition[Pre])(implicit o: Origin): Expr[Post] =
    Result[Post](functionMap.ref(ref.decl))
}