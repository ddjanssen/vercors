/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silver.ast.utility

import org.kiama.util.{Message, Messaging}
import scala.collection.mutable
import util.parsing.input.{Position, NoPosition}
import viper.silver.parser.{PForPerm, PIdnUse, Parser}
import viper.silver.ast._
import scala.Seq

/** An utility object for consistency checking. */
object Consistency {
  var messages: Messaging.Messages = Nil
  def recordIfNot(suspect: Positioned, property: Boolean, message: String) {
    if (!property) {
      val pos = suspect.pos match {
        case rp: AbstractSourcePosition =>
          new Position {
            val line = rp.line
            val column = rp.column
            val lineContents = "<none>"
          }
        case rp: HasLineColumn =>
          new Position {
            val line = rp.line
            val column = rp.column
            val lineContents = "<none>"
          }
        case rp@viper.silver.ast.NoPosition => NoPosition
      }

      this.messages ++= Messaging.aMessage(Message(message,pos))  // this is the way to contruct a message directly with a position (only).
    }
  }

  /** Reset the Kiama messages */
  def resetMessages { this.messages = Nil }
  /** Names that are not allowed for use in programs. */
  def reservedNames: Seq[String] = Parser.reserved

  /** Returns true iff the string `name` is a valid identifier. */
  def validIdentifier(name: String) = ("^" + Parser.identifier + "$").r.findFirstIn(name).isDefined

  /** Returns true iff the string `name` is a valid identifier, and not a reserved word. */
  def validUserDefinedIdentifier(name: String) = validIdentifier(name) && !reservedNames.contains(name)

  /** Returns true iff the two arguments are of equal length. */
  def sameLength[S, T](a: Seq[T], b: Seq[S]) = a.size == b.size

  /** Returns true iff the first argument can be assigned to the second one,
    * i.e. if the type of the first one is a subtype of the type of the second one. */
  def isAssignable(a: Typed, b: Typed) = a isSubtype b

  /** Returns true iff the arguments are equal of length and
    * the elements of the first argument are assignable to the corresponding elements of the second argument */
  def areAssignable(a: Seq[Typed], b: Seq[Typed]) = sameLength(a, b) && ((a zip b) forall (t => isAssignable(t._1, t._2)))

  /** Returns true iff there are no duplicates. */
  def noDuplicates[T](a: Seq[T]) = a.distinct.size == a.size

  /** Returns true if the given node contains no old expression. */
  def noOld(n: Node) = !n.existsDefined { case _: Old => }

  /** Returns true if the given node contains no result. */
  def noResult(n: Node) = !n.existsDefined { case _: Result => }

  /** Returns true if the given node contains no perm expression.*/
  def noPerm(n: Node)  = !n.existsDefined { case _: CurrentPerm => }

  /** Returns true if the given node contains no forallrefs expression.*/
  def noForallRefs(n: Node)  = !n.existsDefined { case _: ForPerm => }

  /** Returns true if the given node contains no access locations. */
  def noAccessLocation(n: Node) = !n.existsDefined { case _: LocationAccess => }

  /** Convenience methods to treat null values as some other default values (e.g treat null as empty List) */
  def nullValue[T](a: T, b: T) = if (a != null) a else b

  /**
   * Checks that this boolean expression contains no subexpressions that can appear in positive positions (i.e. in
   * conjuncts or on the right side of implications or conditional expressions) only, i.e. no access predicates and
   * no InhaleExhaleExp.
   */
  def checkNoPositiveOnly(e: Exp) = {
    recordIfNot(e, hasNoPositiveOnly(e), s"$e is non pure and appears where only pure expressions are allowed.")
  }

  /**
   * Does this boolean expression contain no subexpressions that can appear in positive positions only?
   * @param exceptInhaleExhale Are inhale-exhale expressions possible?
   *                           Default: false.
   */
  def hasNoPositiveOnly(e: Exp, exceptInhaleExhale: Boolean = false): Boolean = e match {
    case _: AccessPredicate => false
    case InhaleExhaleExp(inhale, exhale) => {
      exceptInhaleExhale && hasNoPositiveOnly(inhale, exceptInhaleExhale) && hasNoPositiveOnly(exhale, exceptInhaleExhale)
    }
    case And(left, right) => {
      hasNoPositiveOnly(left, exceptInhaleExhale) && hasNoPositiveOnly(right, exceptInhaleExhale)
    }
    case Implies(_, right) => {
      // The left side is checked during creation of the Implies expression.
      hasNoPositiveOnly(right, exceptInhaleExhale)
    }
    case _ => true // All other cases are checked during creation of the expression.
  }

  /** This is like `checkNoPositiveOnly`, except that inhale-exhale expressions are fine. */
  def checkNoPositiveOnlyExceptInhaleExhale(e: Exp): Unit =
    recordIfNot(e, hasNoPositiveOnly(e, true), s"$e is non pure and appears where only pure expressions are allowed.")

  /** Check all properties required for a function body. */
  def checkFunctionBody(e: Exp) {
    recordIfNot(e, noOld(e), "Old expressions are not allowed in functions bodies.")
    recordIfNot(e, noResult(e), "Result variables are not allowed in function bodies.")
    recordIfNot(e, noForallRefs(e), "Function bodies are not allowed to contain forallrefs expressions")
    recordIfNot(e, noPerm(e), "Function bodies are not allowed to contain perm expressions")
    checkNoPositiveOnly(e)
  }

  /** Checks that none of the given formal arguments are reassigned inside the body. */
  def checkNoArgsReassigned(args: Seq[LocalVarDecl], b: Stmt) {
    val argVars = args.map(_.localVar).toSet
    for (a@LocalVarAssign(l, _) <- b if argVars.contains(l)) {
      recordIfNot(a, false, s"$a is a reassignment of formal argument $l.")
    }
  }

  /** Check all properties required for a precondition. */
  def checkPre(e: Exp) {
    recordIfNot(e, noOld(e), "Old expressions are not allowed in preconditions.")
    recordIfNot(e, noResult(e), "Result variables are not allowed in preconditions.")
    checkNonPostContract(e)
  }

  /** Check all properties required for a contract expression that is not a postcondition (precondition, invariant, predicate) */
  def checkNonPostContract(e: Exp) {
    recordIfNot(e, noResult(e), "Result variables are only allowed in postconditions of functions.")
    checkPost(e)
  }

  def checkPost(e: Exp) {
    recordIfNot(e, e isSubtype Bool, s"Contract $e: ${e.typ} must be boolean.")
  }

  /** Returns true iff the given expression is a valid trigger. */
  def validTrigger(e: Exp): Boolean = {
    e match {
      case Old(nested) => validTrigger(nested) // case corresponds to OldTrigger node
      case e : PossibleTrigger => !(e.existsDefined { case _: ForbiddenInTrigger => })
      case _ => false
    }
  }

  /** Returns true iff the given QuantifiedExp is either pure, or of the shape of quantified permissions allowed (see QuantifiedPermissionSupporter)*/
  def supportedQuantifier(q: QuantifiedExp) : Boolean = q match {
    case QuantifiedPermissionSupporter.ForallRefPerm(_, _, _, _, _, _, _) =>
      true
    case _ => q.isPure
  }

  /**
   * Is the control flow graph starting at `start` well-formed.  That is, does it have the following
   * properties:
   * <ul>
   * <li>It is acyclic.
   * <li>It has exactly one final block that all paths end in and that has no successors.
   * <li>Jumps are only within a loop, or out of (one or several) loops.
   * </ul>
   */
  // TODO: The last property about jumps is not checked as stated, but a stronger property (essentially forbidding many interesting jumps is checked)
  def isWellformedCfg(start: Block): Boolean = {
    val (ok, acyclic, terminalBlocks) = isWellformedCfgImpl(start)
    ok && acyclic && terminalBlocks.size == 1
  }

  /**
   * Implementation of well-formedness check. Returns (ok, acyclic, terminalBlocks) where `ok` refers
   * to the full graph and `acyclic` and `terminalBlocks` only to the outer-most graph (not any loops with nested
   * graphs).
   */
  private def isWellformedCfgImpl(start: Block, seenSoFar: Set[Block] = Set(), okSoFar: Boolean = true): (Boolean, Boolean, Set[TerminalBlock]) = {
    var ok = okSoFar
    start match {
      case t: TerminalBlock => (okSoFar, true, Set(t))
      case _ =>
        start match {
          case LoopBlock(body, cond, invs, locals, succ) =>
            val (loopok, acyclic, terminalBlocks) = isWellformedCfgImpl(body)
            ok = okSoFar && loopok && acyclic && terminalBlocks.size == 1
          case _ =>
        }
        val seen = seenSoFar union Set(start)
        var terminals = Set[TerminalBlock]()
        var acyclic = true
        for (b <- start.succs) {
          if (seen contains b.dest) {
            acyclic = false
          }
          val (okrec, a, t) = isWellformedCfgImpl(b.dest, seen, ok)
          ok = ok && okrec
          acyclic = acyclic && a
          terminals = terminals union t
        }
        (ok, acyclic, terminals)
    }
  }

  def fieldOrPredicate(p : Positioned) : Boolean = p match {
    case Predicate(_,_,_) => true
    case Field(_,_) => true
    case _ => false
  }

  //check only for predicates (everything else yields true)
  def oneRefParam(p : Positioned) : Boolean = p match {
    case p : Predicate => p.formalArgs.size == 1 && p.formalArgs.head.typ == Ref
    case _ => true
  }

  //check all properties that need to be satisfied by the arguments of forallrefs expressions
  def checkForallRefsArguments(arg : Node) {

    val positioned : Positioned = arg match {
      case p : Positioned => p
      case _ => sys.error("Can only handle positioned arguments!")
    }

    recordIfNot(positioned, fieldOrPredicate(positioned), "Can only use fields and predicates in 'forallrefs' expressions")
    recordIfNot(positioned, oneRefParam(positioned), "Can only use predicates with one Ref parameter in 'forallrefs' expressions")
  }
}
