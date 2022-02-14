package vct.col.newrewrite

import hre.util.ScopedStack
import vct.col.ast._
import vct.col.newrewrite.util.{Extract, FreshSuccessionScope, Substitute}
import vct.col.origin._
import vct.col.rewrite.{Generation, Rewriter, RewriterBuilder}
import vct.col.util.AstBuildHelpers
import vct.col.util.AstBuildHelpers._

import scala.collection.mutable
case object ParBlockEncoder extends RewriterBuilder {
  def regionName(region: ParRegion[_]): String = region match {
    case ParParallel(regions) => "par_$" + regions.map(regionName).mkString("_") + "$"
    case ParSequential(regions) => "seq_$" + regions.map(regionName).mkString("_") + "$"
    case block: ParBlock[_] => block.o.preferredName
  }

  case class ParRegionImpl(region: ParRegion[_]) extends Origin {
    override def messageInContext(message: String): String =
      region.o.messageInContext(message)

    override def preferredName: String = "do_" + regionName(region)
  }

  case class ParBlockCheck(block: ParBlock[_]) extends Origin {
    override def preferredName: String =
      "check_" + regionName(block)

    override def messageInContext(message: String): String =
      block.o.messageInContext(message)
  }

  case object ParImpl extends Origin {
    override def preferredName: String = "unknown"
    override def messageInContext(message: String): String =
      s"[At node generated for the implementation of parallel blocks]: $message"
  }

  case class ParBarrierPostconditionFailed(barrier: ParBarrier[_]) extends Blame[CallableFailure] {
    override def blame(error: CallableFailure): Unit = error match {
      case PostconditionFailed(_, failure, _) =>
        barrier.blame.blame(ParBarrierInconsistent(failure, barrier))
      case ctx: ContextEverywhereFailedInPost =>
        PanicBlame("the generated method for a barrier proof does not include context_everywhere clauses.").blame(ctx)
      case _: SignalsFailed | _: ExceptionNotInSignals =>
        barrier.blame.blame(ParBarrierMayNotThrow(barrier))
    }
  }

  case class ParBarrierExhaleFailed(barrier: ParBarrier[_]) extends Blame[ExhaleFailed] {
    override def blame(error: ExhaleFailed): Unit =
      barrier.blame.blame(ParBarrierNotEstablished(error.failure, barrier))
  }

  case class ParPreconditionPostconditionFailed(region: ParRegion[_]) extends Blame[PostconditionFailed] {
    override def blame(error: PostconditionFailed): Unit =
      region.blame.blame(ParPreconditionFailed(error.failure, region))
  }

  case class ParPreconditionPreconditionFailed(region: ParRegion[_]) extends Blame[PreconditionFailed] {
    override def blame(error: PreconditionFailed): Unit =
      region.blame.blame(ParPreconditionFailed(error.failure, region))
  }

  case class ParPostconditionImplementationFailure(block: ParBlock[_]) extends Blame[CallableFailure] {
    override def blame(error: CallableFailure): Unit = error match {
      case PostconditionFailed(_, failure, _) =>
        block.blame.blame(ParBlockPostconditionFailed(failure, block))
      case ctx: ContextEverywhereFailedInPost =>
        PanicBlame("the generated method for a parallel block thread does not include context_everywhere clauses.").blame(ctx)
      case SignalsFailed(failure, _) =>
        block.blame.blame(ParBlockMayNotThrow(failure, block))
      case ExceptionNotInSignals(failure, _) =>
        block.blame.blame(ParBlockMayNotThrow(failure, block))
    }
  }

  case class EmptyHintCannotThrow(inner: Blame[PostconditionFailed]) extends Blame[CallableFailure] {
    override def blame(error: CallableFailure): Unit = error match {
      case err: PostconditionFailed => inner.blame(err)
      case _: ContextEverywhereFailedInPost =>
        PanicBlame("A procedure generated to prove an implication does not have context_everywhere clauses.").blame(error)
      case _: SignalsFailed | _: ExceptionNotInSignals =>
        PanicBlame("A procedure that proves an implication, of which the body is the nop statement cannot throw an exception.").blame(error)
    }
  }
}

case class ParBlockEncoder[Pre <: Generation]() extends Rewriter[Pre] {
  import ParBlockEncoder._

  val invariants: ScopedStack[Expr[Pre]] = ScopedStack()
  val parDecls: mutable.Map[ParBlockDecl[Pre], ParBlock[Pre]] = mutable.Map()

  def quantify(block: ParBlock[Pre], expr: Expr[Pre])(implicit o: Origin): Expr[Pre] = {
    val quantVars = block.iters.map(_.variable).map(v => v -> new Variable[Pre](v.t)(v.o)).toMap
    val body = Substitute(quantVars.map { case (l, r) => Local[Pre](l.ref) -> Local[Pre](r.ref) }.toMap[Expr[Pre], Expr[Pre]]).dispatch(expr)
    block.iters.foldLeft(body)((body, iter) => {
      val v = quantVars(iter.variable)
      Starall(Seq(v), Nil, (iter.from <= v.get && v.get < iter.to) ==> body)
    })
  }

  def proveImplies(blame: Blame[PostconditionFailed], antecedent: Expr[Post], consequent: Expr[Post])(implicit origin: Origin): Unit = {
    proveImplies(EmptyHintCannotThrow(blame), antecedent, consequent, Block(Nil))
  }

  def proveImplies(blame: Blame[CallableFailure], antecedent: Expr[Post], consequent: Expr[Post], hint: Statement[Post])(implicit origin: Origin): Unit = {
    val (Seq(req, ens), bindings) =
      Extract.extract(antecedent, consequent)

    procedure[Post](
      blame = blame,
      requires = UnitAccountedPredicate(req),
      ensures = UnitAccountedPredicate(ens),
      args = bindings.keys.toSeq,
      body = Some(hint),
    ).declareDefault(this)
  }

  def requires(region: ParRegion[Pre], includingInvariant: Boolean = false)(implicit o: Origin): Expr[Pre] = region match {
    case ParParallel(regions) => AstBuildHelpers.foldStar(regions.map(requires(_, includingInvariant)))
    case ParSequential(regions) => regions.headOption.map(requires(_, includingInvariant)).getOrElse(tt)
    case block: ParBlock[Pre] =>
      if(includingInvariant) block.context_everywhere &* quantify(block, block.requires) else quantify(block, block.requires)
  }

  def ensures(region: ParRegion[Pre], includingInvariant: Boolean = false)(implicit o: Origin): Expr[Pre] = region match {
    case ParParallel(regions) => AstBuildHelpers.foldStar(regions.map(ensures(_, includingInvariant)))
    case ParSequential(regions) => regions.lastOption.map(ensures(_, includingInvariant)).getOrElse(tt)
    case block: ParBlock[Pre] =>
      if(includingInvariant) block.context_everywhere &* quantify(block, block.ensures) else quantify(block, block.ensures)
  }

  val regionAsMethod: mutable.Map[ParRegion[Pre], (Procedure[Post], Seq[Expr[Post]])] = mutable.Map()

  def getRegionMethod(region: ParRegion[Pre]): (Procedure[Post], Seq[Expr[Post]]) = {
    implicit val o: Origin = ParImpl
    regionAsMethod.getOrElseUpdate(region, region match {
      case ParParallel(regions) =>
        val (Seq(req, ens, inv), vars) = Extract.extract[Pre](requires(region, includingInvariant = true), ensures(region, includingInvariant = true), foldAnd(invariants.toSeq))
        val result = procedure[Post](
          blame = AbstractApplicable,
          args = collectInScope(variableScopes) { vars.keys.foreach(dispatch) },
          requires = UnitAccountedPredicate(FreshSuccessionScope(this).dispatch(inv &* req)),
          ensures = UnitAccountedPredicate(FreshSuccessionScope(this).dispatch(inv &* ens)),
        )(ParRegionImpl(region))
        result.declareDefault(this)
        (result, vars.values.map(dispatch).toSeq)
      case ParSequential(regions) =>
        val (Seq(req, ens, inv), vars) = Extract.extract[Pre](requires(region, includingInvariant = true), ensures(region, includingInvariant = true), foldAnd(invariants.toSeq))

        val result = procedure[Post](
          blame = AbstractApplicable,
          args = collectInScope(variableScopes) { vars.keys.foreach(dispatch) },
          requires = UnitAccountedPredicate(FreshSuccessionScope(this).dispatch(inv &* req)),
          ensures = UnitAccountedPredicate(FreshSuccessionScope(this).dispatch(inv &* ens)),
        )(ParRegionImpl(region))
        result.declareDefault(this)
        (result, vars.values.map(dispatch).toSeq)
      case block: ParBlock[Pre] =>
        invariants.having(block.context_everywhere) {
          val (Seq(req, ens, inv), vars) = Extract.extract[Pre](requires(block), ensures(block), foldAnd(invariants.toSeq))

          val result = procedure[Post](
            blame = AbstractApplicable,
            args = collectInScope(variableScopes) { vars.keys.foreach(dispatch) },
            requires = UnitAccountedPredicate(FreshSuccessionScope(this).dispatch(inv &* req)),
            ensures = UnitAccountedPredicate(FreshSuccessionScope(this).dispatch(inv &* ens)),
          )(ParRegionImpl(region))
          result.declareDefault(this)
          (result, vars.values.map(dispatch).toSeq)
        }
    })
  }

  def emitChecks(region: ParRegion[Pre]): Unit = region match {
    case ParParallel(regions) =>
      // The parallel composition of regions is automatically valid
      regions.foreach(emitChecks)
    case ParSequential(regions) =>
      // For sequential composition we verify that pairs of sequentially composed regions have a matching post- and precondition
      implicit val o: Origin = region.o
      regions.zip(regions.tail).foreach {
        case (left, right) =>
          proveImplies(
            ParPreconditionPostconditionFailed(right),
            FreshSuccessionScope(this).dispatch(ensures(left, includingInvariant = true)),
            FreshSuccessionScope(this).dispatch(requires(right, includingInvariant = true))
          )
      }
      regions.foreach(emitChecks)
    case block @ ParBlock(decl, iters, ctx, req, ens, content) =>
      // For blocks we generate a separate check, by checking the contract for an indeterminate iteration
      parDecls(decl) = block
      decl.drop()
      implicit val o: Origin = region.o
      val extract = Extract[Pre]()

      val ranges = iters.map {
        case IterVariable(v, from, to) =>
          v.drop()
          from <= v.get && v.get < to
      }.map(extract.extract)

      val requires = extract.extract(req)
      val ensures = extract.extract(ens)
      val context = extract.extract(ctx)
      val invariant = extract.extract(foldAnd(invariants.toSeq))

      val body = extract.extract(content)

      val vars = extract.finish()

      val args = collectInScope(variableScopes) { vars.keys.foreach(dispatch) }

//      println(s"At ${decl.o.preferredName}:")
//      println(s"    - requires = $requires")
//      println(s"    - ensures = $ensures")
//      println(s"    - invariant = $invariant")
//      println(s"    - ranges = ${foldAnd(ranges)}")
//      println(s"    - context = $context")
//      println()

      val invariantHere = FreshSuccessionScope(this).dispatch(invariant && context && foldAnd(ranges))

      invariants.having(context && foldAnd(ranges)) {
        procedure(
          blame = ParPostconditionImplementationFailure(block),
          args = args,
          requires = UnitAccountedPredicate(invariantHere &* dispatch(requires)),
          ensures = UnitAccountedPredicate(invariantHere &* dispatch(ensures)),
          body = Some(dispatch(body)),
        )(ParBlockCheck(block)).declareDefault(this)
      }
  }

  override def dispatch(stat: Statement[Pre]): Statement[Post] = stat match {
    case parRegion @ ParStatement(impl) =>
      implicit val o: Origin = parRegion.o
      val (proc, args) = getRegionMethod(impl)
      emitChecks(impl)
      Eval(ProcedureInvocation[Post](proc.ref, args, Nil, Nil, Nil, Nil)(NoContext(ParPreconditionPreconditionFailed(impl))))

    case parBarrier @ ParBarrier(blockRef, invs, requires, ensures, content) =>
      implicit val o: Origin = parBarrier.o
      val block = parDecls(blockRef.decl)
      // TODO: it is a type-check error to have an invariant reference be out of scope in a barrier
      proveImplies(ParBarrierPostconditionFailed(parBarrier), dispatch(quantify(block, requires)), dispatch(quantify(block, ensures)), hint = dispatch(content))

      Block(Seq(
        Exhale(dispatch(requires))(ParBarrierExhaleFailed(parBarrier)),
        Inhale(dispatch(ensures)),
      ))

    case other => rewriteDefault(other)
  }
}
