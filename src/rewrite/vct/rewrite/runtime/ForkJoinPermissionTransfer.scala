package vct.rewrite.runtime

import hre.util.ScopedStack
import vct.col.ast.RewriteHelpers.{RewriteInstanceMethod, RewriteScope}
import vct.col.ast._
import vct.col.origin.Origin
import vct.col.rewrite.{Generation, Rewriter, RewriterBuilder, Rewritten}
import vct.col.util.AstBuildHelpers._
import vct.rewrite.runtime.util.Util._
import vct.rewrite.runtime.util.TransferPermissionRewriter

import scala.collection.mutable
import scala.language.postfixOps


object ForkJoinPermissionTransfer extends RewriterBuilder {
  override def key: String = "forkJoinPermissionTransfer"

  override def desc: String = "Detects fork/join/run methods and creates a permission transfer for the forked thread"
}

case class ForkJoinPermissionTransfer[Pre <: Generation]() extends Rewriter[Pre] {

  implicit var program: Program[Pre] = null
  val currentClass: ScopedStack[Class[Pre]] = new ScopedStack()
  val postJoinTokens: ScopedStack[mutable.ArrayBuffer[RuntimePostJoin[Post]]] = new ScopedStack()

  override def dispatch(program: Program[Pre]): Program[Rewritten[Pre]] = {
    this.program = program
    val test = super.dispatch(program)
    test
  }

  def collectTransferPermissionStatementsFromRunMethod(i: InstanceMethod[Pre]): Seq[Statement[Post]] = {
    if (!isExtendingThread(currentClass.top) || !isMethod(i, "run")) return Seq.empty
    implicit val o: Origin = i.o
    val predicate = unfoldPredicate(i.contract.requires).head
    TransferPermissionRewriter(this, currentClass.top, None, None, Seq.empty).addPermissions(predicate)
  }

  protected def dispatchInstanceMethod(i: InstanceMethod[Pre])(implicit o: Origin = i.o): Unit = {
    postJoinTokens.collect {
      variables.collectScoped {
        val transferPermissionsStatements: Seq[Statement[Post]] = collectTransferPermissionStatementsFromRunMethod(i)
        val scope = collectMethodBody(i)
        val scopeBlock = collectBlockScope(scope)
        val newScope = scope.rewrite(body = Block[Post](transferPermissionsStatements :+ dispatch(scopeBlock)))
        classDeclarations.succeed(i, i.rewrite(body = Some(newScope)))
      }
    }
  }

  def getDispatchedOffset(e: Eval[Post]): Expr[Post] = {
    e.expr match {
      case mi: MethodInvocation[Post] => mi.obj
      case _ => ???
    }
  }


  def dispatchJoinInvocation(e: Eval[Pre], mi: MethodInvocation[Pre])(implicit o: Origin = e.o): Statement[Rewritten[Pre]] = {
    val runMethod: InstanceMethod[Pre] = getRunMethod(mi)
    val predicate: Expr[Pre] = unfoldPredicate(runMethod.contract.ensures).head
    val dispatchedStatement: Eval[Post] = super.dispatch(e).asInstanceOf[Eval[Post]]
    val dispatchedOffset: Expr[Post] = getDispatchedOffset(dispatchedStatement)
    val factor = Some(postJoinTokens.top.find(rpj => rpj.obj == dispatchedOffset).get.arg)
    val newAddStatements = TransferPermissionRewriter(this, currentClass.top, None, factor, Seq.empty).addPermissions(predicate)
    val removeStatements = TransferPermissionRewriter(this, currentClass.top, Some(mi.obj), factor, Seq(mi.obj.asInstanceOf[Local[Pre]].ref.decl)).removePermissions(predicate)
    Block[Post](dispatchedStatement +: (newAddStatements ++ removeStatements))
  }

  def dispatchStartInvocation(e: Eval[Pre], mi: MethodInvocation[Pre])(implicit o: Origin = e.o): Statement[Rewritten[Pre]] = {
    val runMethod: InstanceMethod[Pre] = getRunMethod(mi)
    val predicate: Expr[Pre] = unfoldPredicate(runMethod.contract.requires).head
    val dispatchedStatement: Eval[Post] = super.dispatch(e).asInstanceOf[Eval[Post]]
    val removeStatements = TransferPermissionRewriter(this, currentClass.top, None, None, Seq.empty).removePermissions(predicate)
    Block[Post](removeStatements :+ dispatchedStatement)
  }

  override def dispatch(stat: Statement[Pre]): Statement[Rewritten[Pre]] = {
    stat match {
      case rpj: RuntimePostJoin[Pre] if postJoinTokens.nonEmpty => {
        val newRpj = super.dispatch(rpj)
        postJoinTokens.top.addOne(newRpj.asInstanceOf[RuntimePostJoin[Post]])
        newRpj
      }
      case e@Eval(mi: MethodInvocation[Pre]) if isThreadMethod(mi, "join") => dispatchJoinInvocation(e, mi)
      case e@Eval(mi: MethodInvocation[Pre]) if isThreadMethod(mi, "start") => dispatchStartInvocation(e, mi)
      case _ => super.dispatch(stat)
    }
  }


  override def dispatch(decl: Declaration[Pre]): Unit = {
    decl match {
      case cls: Class[Pre] => currentClass.having(cls) {
        super.dispatch(cls)
      }
      case i: InstanceMethod[Pre] => dispatchInstanceMethod(i)
      case _ => super.dispatch(decl)
    }
  }
}