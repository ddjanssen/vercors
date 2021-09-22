package vct.col.ast

import vct.result.VerificationResult

sealed trait ContractFailure
case class ContractFalse(node: Expr) extends ContractFailure {
  override def toString: String = s"it may be false"
}
case class ContractNotWellformed()
case class InsufficientPermissionToExhale(node: SilverResource) extends ContractFailure {
  override def toString: String = s"there might not be enough permission to exhale this amount"
}
case class ReceiverNotInjective(node: SilverResource) extends ContractFailure {
  override def toString: String = s"the location in this permission predicate may not be injective with regards to the quantified variables"
}
case class NegativePermissionValue(node: SilverResource) extends ContractFailure {
  override def toString: String = s"the amount of permission in this permission predicate may be negative"
}

trait VerificationFailure

case class InternalError(description: String) extends VerificationFailure {
  override def toString: String = s"An internal error occurred: $description."
}
case class SilverAssignFailed(assign: SilverFieldAssign) extends VerificationFailure {
  override def toString: String = s"Insufficient permission to assign to field."
}
case class AssertFailed(failure: ContractFailure, assertion: Assert) extends VerificationFailure {
  override def toString: String = s"Assertion may not hold, since $failure."
}
case class ExhaleFailed(failure: ContractFailure, exhale: Exhale) extends VerificationFailure {
  override def toString: String = s"Exhale may fail, since $failure."
}
case class SilverUnfoldFailed(failure: ContractFailure, unfold: SilverUnfold) extends VerificationFailure {
  override def toString: String = s"Unfold may fail, since $failure."
}
case class SilverFoldFailed(failure: ContractFailure, fold: SilverFold) extends VerificationFailure {
  override def toString: String = s"Fold may fail, since $failure"
}
case class PreconditionFailed(failure: ContractFailure, invocation: Invocation) extends VerificationFailure {
  override def toString: String = s"Precondition may not hold, since $failure."
}
case class PostconditionFailed(failure: ContractFailure, invokable: ContractApplicable) extends VerificationFailure {
  override def toString: String = s"Postcondition may not hold, since $failure."
}
sealed trait SilverWhileInvariantFailure extends VerificationFailure
case class SilverWhileInvariantNotEstablished(failure: ContractFailure, loop: SilverWhile) extends SilverWhileInvariantFailure {
  override def toString: String = s"This invariant may not be established, since $failure."
}
case class SilverWhileInvariantNotMaintained(failure: ContractFailure, loop: SilverWhile) extends SilverWhileInvariantFailure {
  override def toString: String = s"This invariant may not be maintained, since $failure."
}
case class DivByZero(div: DividingExpr) extends VerificationFailure {
  override def toString: String = s"The divisor may be zero."
}
case class SilverInsufficientPermission(deref: SilverDeref) extends VerificationFailure {
  override def toString: String = s"There may be insufficient permission to access this field here."
}
case class InsufficientPermission(deref: Deref) extends VerificationFailure {
  override def toString: String = s"There may be insufficient permission to access this field here."
}
case class LabelNotReached(old: Old) extends VerificationFailure {
  override def toString: String = s"The label mentioned in this old expression may not be reached at the time the old expression is reached."
}
sealed trait SeqBoundFailure extends VerificationFailure
case class SeqBoundNegative(subscript: SeqSubscript) extends SeqBoundFailure {
  override def toString: String = s"The index in this sequence subscript may be negative."
}
case class SeqBoundExceedsLength(subscript: SeqSubscript) extends SeqBoundFailure {
  override def toString: String = s"The index in this sequence subscript may exceed the length of the sequence."
}

case class ParInvariantNotEstablished(failure: ContractFailure, invariant: ParInvariant) extends VerificationFailure {
  override def toString: String = s"This parallel invariant may not be established, since $failure."
}
sealed trait ParBarrierFailed extends VerificationFailure
case class ParBarrierNotEstablished(failure: ContractFailure, barrier: ParBarrier) extends ParBarrierFailed {
  override def toString: String = s"The precondition of this barrier may not hold, since $failure."
}
case class ParBarrierInconsistent(failure: ContractFailure, barrier: ParBarrier) extends ParBarrierFailed {
  override def toString: String = s"The precondition of this barrier is not consistent with the postcondition, since this postcondition may not hold, because $failure."
}
sealed trait ParRegionFailed extends VerificationFailure
case class ParRegionPreconditionFailed(failure: ContractFailure, region: ParRegion) extends ParRegionFailed {
  override def toString: String = s"The precondition of this region may not hold, since $failure."
}
sealed trait ParRegionInconsistent extends ParRegionFailed {
  def failure: ContractFailure
  override def toString: String = s"The contract of the parallel region is inconsistent with the joint contracts of its blocks: $direction, since $failure."
  def direction: String
}
case class ParRegionPreconditionDoesNotImplyBlockPreconditions(failure: ContractFailure, region: ParRegion) extends ParRegionInconsistent {
  override def direction: String = s"the precondition of the region does not imply the preconditions of its blocks"
}
case class ParRegionPostconditionNotImpliedByBlockPostconditions(failure: ContractFailure, region: ParRegion) extends ParRegionInconsistent {
  override def direction: String = s"the postcondition of the region does not follow from the postconditions of its blocks"
}

case class ModelInsufficientPermission(deref: ModelDeref) extends VerificationFailure {
  override def toString: String = s"There may be insufficient permission to access this model field here."
}

trait Blame[-T <: VerificationFailure] {
  def blame(error: T): Unit
}

case class BlameUnreachable(message: String, failure: VerificationFailure) extends VerificationResult.SystemError {
  def text: String = s"An error condition was reached, which should be statically unreachable. $message ($failure)"
}

case class PanicBlame(message: String) extends Blame[VerificationFailure] {
  override def blame(error: VerificationFailure): Unit = throw BlameUnreachable(message, error)
}

object DerefAssignTarget extends PanicBlame("Assigning to a field should trigger an error on the assignment, and not on the dereference.")
object DerefPerm extends PanicBlame("Dereferencing a field in a permission should trigger an error on the permission, not on the dereference.")
object UnresolvedDesignProblem extends PanicBlame("The design does not yet accommodate passing a meaningful blame here")