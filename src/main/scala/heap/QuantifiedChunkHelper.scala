/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper
package silicon
package heap

import silver.verifier.PartialVerificationError
import silver.verifier.reasons.{InsufficientPermission, ReceiverNull}
import silver.ast.utility
import ast.{Field, LocationAccess}
import interfaces.{VerificationResult, Failure}
import interfaces.state.{Chunk, ChunkIdentifier, Store, Heap, PathConditions, State, StateFactory}
import interfaces.decider.Decider
import state.{DefaultContext, SymbolConvert, QuantifiedChunk, FieldChunkIdentifier, DirectFieldChunk}
import state.terms.utils.BigPermSum
import state.terms._
import state.terms.predef.`?r`

class QuantifiedChunkHelper[ST <: Store[ST],
                            H <: Heap[H],
                            PC <: PathConditions[PC],
                            S <: State[ST, H, S]]
                           (decider: Decider[DefaultFractionalPermissions, ST, H, PC, S, DefaultContext],
                            symbolConverter: SymbolConvert,
                            stateFactory: StateFactory[ST, H, S]) {

  import symbolConverter.toSort
  import stateFactory._
  import decider.{assume, assert, check, fresh}

  private type C = DefaultContext

  private case class FvfDefEntry(partialValue: Term, valueTriggers: Seq[Trigger], partialDomain: Domain)

  private case class FvfDef(field: Field, fvf: Term, entries: Seq[FvfDefEntry]) {
    lazy val singletonValues = entries map (entry => entry.partialValue)

    def quantifiedValues(qvar: Var) =
      entries map (entry => Forall(qvar, entry.partialValue, entry.valueTriggers))

    lazy val totalDomain = (
      Domain(field.name, fvf)
        ===
      entries.tail.foldLeft[SetTerm](entries.head.partialDomain)((dom, entry) => SetUnion(dom, entry.partialDomain)))
  }

  /* Chunk creation */

  def createSingletonQuantifiedChunk(rcvr: Term,
                                     field: String,
                                     value: Term,
                                     perms: DefaultFractionalPermissions)
                                    : QuantifiedChunk = {

    Predef.assert(value.sort.isInstanceOf[sorts.FieldValueFunction],
                  s"Quantified chunk values must be of sort FieldValueFunction, but found value $value of sort ${value.sort}")

    val condPerms = singletonConditionalPermissions(rcvr, perms)

    QuantifiedChunk(field, value, condPerms)
  }

  def singletonConditionalPermissions(rcvr: Term, perms: DefaultFractionalPermissions)
                                     : DefaultFractionalPermissions = {

    TermPerm(Ite(`?r` === rcvr, perms, NoPerm()))
  }

  def createQuantifiedChunk(qvar: Var,
                            rcvr: Term,
                            field: Field,
                            value: Term,
                            perms: DefaultFractionalPermissions,
                            condition: Term)
                           : QuantifiedChunk = {

    Predef.assert(value.sort.isInstanceOf[sorts.FieldValueFunction],
                  s"Quantified chunk values must be of sort FieldValueFunction, but found value $value of sort ${value.sort}")

    val (inverseFunc, inverseFuncAxioms) = getFreshInverseFunction(rcvr, condition, qvar)
    val arbitraryInverseRcvr = inverseFunc(`?r`)
    val condPerms = conditionalPermissions(qvar, arbitraryInverseRcvr, condition, perms)

    decider.assume(inverseFuncAxioms)

    QuantifiedChunk(field.name, value, condPerms)
  }

  def conditionalPermissions(qvar: Var,
                             arbitraryInverseRcvr: Term,
                             qvarSpecificCondition: Term,
                             perms: DefaultFractionalPermissions)
                            : DefaultFractionalPermissions = {

    val arbitraryCondition = qvarSpecificCondition.replace(qvar, arbitraryInverseRcvr)
    val arbitraryPerms = perms.replace(qvar, arbitraryInverseRcvr)

    TermPerm(Ite(arbitraryCondition, arbitraryPerms/*perms*/, NoPerm()))
  }

  /* State queries */

  def getQuantifiedChunk(h: H, field: String) =
    h.values.find {
      case ch: QuantifiedChunk => ch.name == field
      case _ => false
    }.asInstanceOf[Option[QuantifiedChunk]]

  def isQuantifiedFor(h: H, field: String) = getQuantifiedChunk(h, field).nonEmpty

  /**
    * Computes the total permission amount held in the given heap for the given chunk identifier.
    */
  def permission(h: H, id: ChunkIdentifier): DefaultFractionalPermissions = {
    val perms = h.values.toSeq.collect {
      case permChunk: QuantifiedChunk if permChunk.name == id.name => permChunk.perm.replace(`?r`, id.args.last)
    }.asInstanceOf[Iterable[DefaultFractionalPermissions]]

    BigPermSum(perms, Predef.identity)
  }

//  def withSingleValue(σ: S,
//                      h: H,
//                      rcvr: Term,
//                      field: Field,
//                      pve: PartialVerificationError,
//                      locacc: LocationAccess,
//                      c: C)
//                     (Q: Lookup => VerificationResult)
//                     : VerificationResult = {
//
//    withValue(σ, h, rcvr, None, field, pve, locacc, c)((t, fvfDef) => {
//      assume(fvfDef.singletonValues)
//      assume(fvfDef.totalDomain)
//      Q(t)})
//  }

  def withPotentiallyQuantifiedValue(σ: S,
                                     h: H,
                                     rcvr: Term,
                                     optQVarInRcvr: Option[Var],
                                     field: Field,
                                     pve: PartialVerificationError,
                                     locacc: LocationAccess,
                                     c: C)
                                    (Q: Lookup => VerificationResult)
                                    : VerificationResult = {

    withValue(σ, h, rcvr, field, pve, locacc, c)((t, fvfDef) => {
      optQVarInRcvr match {
        case Some(qvar) => assume(fvfDef.quantifiedValues(qvar))
        case None => assume(fvfDef.singletonValues)
      }

      assume(fvfDef.totalDomain)

      Q(t)})
  }

  /* TODO: Is conceptually very close to split(...) since the latter also computes a
   *       field value function over all chunks for a given field.
   *       It would be great to merge the code, while still being able to just compute
   *       a value without manipulating the visited heap chunks.
   */

  private def withValue(σ: S,
                        h: H,
                        rcvr: Term,
                        field: Field,
                        pve: PartialVerificationError,
                        locacc: LocationAccess,
                        c: C)
                       (Q: (Lookup, FvfDef) => VerificationResult)
                       : VerificationResult = {

    assert(σ, rcvr !== Null()) {
      case false =>
        Failure[ST, H, S](pve dueTo ReceiverNull(locacc))

      case true =>
        assert(σ, NoPerm() < permission(h, FieldChunkIdentifier(rcvr, field.name))) {
          case false =>
            Failure[ST, H, S](pve dueTo InsufficientPermission(locacc))

          case true =>
            val fvf = fresh("fvf", sorts.FieldValueFunction(toSort(field.typ)))
            val lookupRcvr = Lookup(field.name, fvf, rcvr)

            var fvfDefs: List[FvfDefEntry] = Nil
            var fvfIndividualDomains: List[Domain] = Nil

            h.values.foreach {
              case ch: QuantifiedChunk if ch.name == field.name =>
                val permsIndividual = ch.perm.replace(`?r`, rcvr).asInstanceOf[DefaultFractionalPermissions]
                val valueIndividual = ch.value.replace(`?r`, rcvr)
                val lookupIndividual = Lookup(field.name, valueIndividual, rcvr)

                fvfDefs ::=
                    FvfDefEntry(Implies(permsIndividual > NoPerm(), lookupRcvr === lookupIndividual),
//                              Trigger(lookupRcvr :: lookupIndividual :: Nil) :: Nil,
                              Trigger(lookupRcvr :: Nil) :: Trigger(lookupIndividual :: Nil) :: Nil,
                              Domain(field.name, valueIndividual))

              case ch if ch.name == field.name =>
                sys.error(s"I did not expect non-quantified chunks on the heap for field ${field.name}, but found $ch")

              case _ => /* Ignore other chunks */
            }

            Q(lookupRcvr, FvfDef(field, fvf, fvfDefs))}}
  }

  /* Manipulating quantified chunks */

  /** Replaces all non-quantified chunks for `field` in `h` with a corresponding
    * quantified chunk. That is, each chunk `x.field |-> v # p` will be replaced
    * with a chunk `forall ?r :: r.field |-> fvf # ?r == x ? W : Z`, and the
    * original value will be preserved by the assumption that `fvf(x) == v` (for
    * a fresh field value function `fvf`, see `createFieldValueFunction`).
    *
    * `h` remains unchanged if it contains no non-quantified chunks for `field`.
    *
    * @param h A heap in which to quantify all chunks for `field`.
    * @param field A field whose chunks in `h` are to be quantified.
    * @return A pair `(h1, ts)` where `h1` is `h` except that all non-quantified
    *         chunks for `field` have been replaced by corresponding quantified
    *         chunks. `ts` is the set of assumptions axiomatising the fresh
    *         field value function `fvf`.
    */
  def quantifyChunksForField(h: H, field: Field): (H, Set[Term]) = {
    val (chunks, ts) =
      h.values.map {
        case ch: DirectFieldChunk if ch.name == field.name =>
          val (fvf, fvfDef) = createFieldValueFunction(field, ch.rcvr, ch.value)
          val qch = createSingletonQuantifiedChunk(ch.rcvr, field.name, fvf, ch.perm)

          (qch, fvfDef)

        case ch =>
          (ch, True())
      }.unzip

    (H(chunks), toSet(ts))
  }

  def quantifyHeapForFields(h: H, fields: Seq[ast.Field]): (H, Set[Term]) = {
    fields.foldLeft((h, Set[Term]())){case ((hAcc, tsAcc), field) =>
      val (h1, ts1) = quantifyChunksForField(hAcc, field)

      (h1, tsAcc ++ ts1)
    }
  }

  def splitSingleLocation(σ: S,
                          h: H,
                          field: Field,
                          concreteReceiver: Term,
                          fraction: DefaultFractionalPermissions,
                          conditionalizedFraction: DefaultFractionalPermissions,
                          c: C)
                         (Q: Option[(H, QuantifiedChunk, C)] => VerificationResult)
                         : VerificationResult = {

    val skolemVar = fresh("sk", sorts.Ref)
    val (h1, ch, fvfDef, success) =
      split(σ, h, field, skolemVar, concreteReceiver, fraction, conditionalizedFraction, c)

    if (success) {
//      assume(fvfDef.singletonValues)
      assume(fvfDef.quantifiedValues(skolemVar))
      assume(fvfDef.totalDomain)
      Q(Some(h1, ch, c))
    } else
      Q(None)
  }

  def splitLocations(σ: S,
                     h: H,
                     field: Field,
                     quantifiedReceiver: Term,
                     qvarInReceiver: Var,
                     fraction: DefaultFractionalPermissions,
                     conditionalizedFraction: DefaultFractionalPermissions,
                     c: C)
                    (Q: Option[(H, QuantifiedChunk, C)] => VerificationResult)
                    : VerificationResult = {

    val (h1, ch, fvfDef, success) =
      split(σ, h, field, quantifiedReceiver, quantifiedReceiver, fraction, conditionalizedFraction, c)

    if (success) {
      assume(fvfDef.quantifiedValues(qvarInReceiver))
      assume(fvfDef.totalDomain)
      Q(Some(h1, ch, c))
    } else
      Q(None)
  }

  private def split(σ: S,
                    h: H,
                    field: Field,
                    arbitraryReceiver: Term,
                    specificReceiver: Term,
                    fraction: DefaultFractionalPermissions,
                    conditionalizedFraction: DefaultFractionalPermissions,
                    c: C)
                   : (H, QuantifiedChunk, FvfDef, Boolean) = {

    def repl(t: Term) = t.replace(`?r`, arbitraryReceiver)

    val (candidates, ignored) = h.values.partition(_.name == field.name) /* TODO: Consider optimising order of chunks */
    var residue: List[Chunk] = Nil
    var permsToTake = conditionalizedFraction
    var success = false
    val fvf = fresh("vs", sorts.FieldValueFunction(toSort(field.typ)))
//    val fvfLookup = Lookup(field.name, fvf, specificReceiver)
    val fvfLookup = Lookup(field.name, fvf, arbitraryReceiver)
    var fvfDefs: List[FvfDefEntry] = Nil

    candidates foreach {
      case ch: QuantifiedChunk =>
        val candidatePerms = repl(ch.perm).asInstanceOf[DefaultFractionalPermissions]
        val candidateValue = repl(ch.value)
        val candidateLookup = Lookup(field.name, candidateValue, arbitraryReceiver)

        fvfDefs ::=
          FvfDefEntry(Implies(candidatePerms > NoPerm(), fvfLookup === candidateLookup),
//                      Trigger(fvfLookup :: candidateLookup :: Nil) :: Nil,
                      Trigger(fvfLookup :: Nil) :: Trigger(candidateLookup :: Nil) :: Nil,
                      Domain(field.name, candidateValue))

        if (success)
          residue ::= ch
        else {
          val constrainPermissions = !silicon.utils.consumeExactRead(fraction, c)

          val permsTaken = PermMin(permsToTake, Ite(`?r` === specificReceiver, ch.perm, NoPerm()))
          permsToTake = permsToTake - permsTaken

          if (constrainPermissions) {
            /* TODO: Add triggers (probably needs autoTriggers for terms ) */
            assume(Forall(`?r`,
                          Implies(ch.perm !== NoPerm(),
                                  conditionalizedFraction < ch.perm), Nil))

            residue ::= ch.copy(perm = ch.perm - permsTaken)
          } else  if (!check(σ, Forall(`?r`, ch.perm - permsTaken === NoPerm(), Nil)))
            residue ::= ch.copy(perm = ch.perm - permsTaken)

          success = check(σ, repl(permsToTake) === NoPerm())
        }

      case ch =>
        sys.error(s"I did not expect non-quantified chunks on the heap for field ${field.name}, but found $ch")
    }

    val hResidue = H(residue ++ ignored)
//    val ch = QuantifiedChunk(specificReceiver, field.name, fvf, conditionalizedFraction)
    val ch = QuantifiedChunk(field.name, fvf, conditionalizedFraction)
    val fvfDef = FvfDef(field, fvf, fvfDefs)

    (hResidue, ch, fvfDef, success)
  }

  /* Misc */

  def createFieldValueFunction(field: Field, rcvr: Term, value: Term): (Term, Term) = value.sort match {
    case _: sorts.FieldValueFunction =>
      /* The value is already a field value function, in which case we don't create a fresh one. */
      (value, True())

    case _ =>
      val fvf = fresh("vs", sorts.FieldValueFunction(toSort(field.typ)))
      val fvfDef = And(Lookup(field.name, fvf, rcvr) === value, Domain(field.name, fvf) === SingletonSet(rcvr))

      (fvf, fvfDef)
  }

  def injectivityAxiom(qvar: Var, condition: Term, receiver: Term, triggers: Option[Seq[Trigger]]) = {
    val vx = Var("x", qvar.sort)
    val vy = Var("y", qvar.sort)

//    val trigger = Trigger(triggerTerms.map(_.replace(qvar, vx)) ++ triggerTerms.map(_.replace(qvar, vy)))

    Forall(vx :: vy :: Nil,
      Implies(
        And(
          condition.replace(qvar, vx),
          condition.replace(qvar, vy),
          receiver.replace(qvar, vx) === receiver.replace(qvar, vy)),
        vx === vy),
      triggers.getOrElse(Nil))
  }

  def injectivityAxiomTriggers(qvar: ast.LocalVariable,
                               condition: ast.Expression,
                               receiver: ast.Expression,
                               tQVar: Var,
                               possibleTriggers: Map[ast.Expression, Term])
                              : Option[Seq[Trigger]] = {

    /* ATTENTION: It is important that the quantified variables used here have
     * the same names as the quantified variables used in method injectivityAxiom!
     */
    val vxDecl = silver.ast.LocalVarDecl("x", qvar.typ)()
    val vyDecl = silver.ast.LocalVarDecl("y", qvar.typ)()
    val vx = vxDecl.localVar
    val vy = vyDecl.localVar
    val tVX = Var(vx.name, toSort(vx.typ))
    val tVY = Var(vy.name, toSort(vy.typ))

    /* Taking all possible trigger terms yields much more restrictive triggers.
     * Compared to using autoTrigger (see below), they reduce the verification
     * time of seqsinglelements.sil from 50s to 30s.
     */
    val triggerTerms = possibleTriggers.values.toSeq
    val trigger = Trigger(triggerTerms.map(_.replace(tQVar, tVX)) ++ triggerTerms.map(_.replace(tQVar, tVY)))
    Some(trigger :: Nil)

//    /* Get triggers by
//     *   1. Creating an AST representation of the injectivity axiom
//     *   2. Get triggers from autoTrigger
//     *   3. Lookup those triggers in the list of recorded possible triggers to
//     *      get the corresponding trigger terms
//     */
//    val axiom =
//      ast.Forall(vxDecl :: vyDecl :: Nil,
//        Nil,
//        ast.Implies(
//          ast.And(
//            ast.And(
//              condition.replace(qvar, vx),
//              condition.replace(qvar, vy)
//            )(),
//            ast.Equals(
//              receiver.replace(qvar, vx),
//              receiver.replace(qvar, vy)
//            )())(),
//          ast.Equals(vx, vy)()
//        )())()
//
//    val axiomWithTriggers = axiom.autoTrigger
//
//    if (axiom.variables == axiomWithTriggers.variables) {
//      val triggers =
//        axiomWithTriggers.triggers.map { trigger =>
//          val triggerTerms =
//            trigger.exps.flatMap { triggerExp =>
//              // TODO: Log a warning if a trigger couldn't be looked up
//              val trx = possibleTriggers.get(triggerExp.replace(vx, qvar))
//                                        .map(_.replace(tQVar, tVX))
//
//              val tr = trx.orElse(possibleTriggers.get(triggerExp.replace(vy, qvar))
//                                                  .map(_.replace(tQVar, tVY)))
//
//              tr
//            }
//
//          println(s"triggerTerms = $triggerTerms")
//          Trigger(triggerTerms)
//        }
//
//      Some(triggers)
//    } else {
//      println(s"Different quantified variables! ${axiom.variables} vs ${axiomWithTriggers.variables}")
//      // TODO: Log a warning that the axiom with triggers has additional quantified variables
//      None
//    }
  }

  def receiverNonNullAxiom(qvar: Var, perm: Term, rcvr: Term, possibleTriggers: Map[ast.Expression, Term]) = {
    /* Constructing a forall-AST, calling autoTrigger and looking up the
     * computed triggers in the map of recorded possible triggers is complicated
     * by the need for using the rewritten permission expression, for which we
     * don't have a corresponding AST node (we could create one, though.)
     */

    val triggerTerms = Nil
    /* WARNING: Using the recorded possible triggers results in matching loops */
//    val triggerTerms = possibleTriggers.values.filter(_.hasSubterm(qvar)).toSeq
//    val triggerTerms = possibleTriggers.values.toSeq

    Forall(qvar,
      Implies(NoPerm() < perm.replace(`?r`, rcvr).asInstanceOf[DefaultFractionalPermissions],
        rcvr !== Null()),
      Trigger(triggerTerms))
  }

  def getFreshInverseFunction(of: Term, condition: Term, qvar: Var): (Term => Term, Seq[Term]) = {
    Predef.assert(of.sort == sorts.Ref, s"Expected ref-sorted term, but found $of of sort ${of.sort}")

    val codomainSort = qvar.sort
    val funcSort = sorts.Arrow(of.sort, codomainSort)
    val funcSymbol = decider.fresh("inv", funcSort)
    val inverseFunc = (t: Term) => Apply(funcSymbol, t :: Nil)

    val ax1 = Forall(qvar, Implies(condition, inverseFunc(of) === qvar), Trigger(of :: Nil) :: Nil)

    val r = Var("r", sorts.Ref)
    val ax2 = Forall(r, Implies(condition, of.replace(qvar, inverseFunc(r)) === r), Trigger(inverseFunc(r) :: Nil) :: Nil)

    (inverseFunc, ax1 :: ax2 :: Nil)
  }
}

object QuantifiedChunkHelper {
  object ForallRef {
    def unapply(n: ast.Node): Option[(ast.LocalVariable,  /* Quantified variable */
                                      ast.Expression,     /* Condition */
                                      ast.Expression,     /* Receiver e of acc(e.f, p) */
                                      ast.Field,          /* Field f of acc(e.f, p) */
                                      ast.Expression,     /* Permissions p of acc(e.f, p) */
                                      ast.Forall,         /* AST node of the forall (for error reporting) */
                                      ast.FieldAccess)] = /* AST node for e.f (for error reporting) */

      n match {
        case forall @ ast.Forall(Seq(lvd @ silver.ast.LocalVarDecl(_, _/*ast.types.Ref*/)),
                                triggers,
                                ast.Implies(condition, ast.FieldAccessPredicate(fa @ ast.FieldAccess(rcvr, f), gain)))
            if    rcvr.exists(_ == lvd.localVar)
               && triggers.isEmpty =>

          /* TODO: If the condition is just "x in xs" then xs could be returned to
           *       simplify the definition of domains of freshly created FVFs (as
           *       done, e.g., when producing quantified permissions) by just
           *       assuming "domain(fvf) == xs" instead of assuming that
           *       "forall x :: x in domain(fvf) <==> condition(x)". This should
           *       have a positive effect on the prover's runtime.
           *
           *       Another way of handling this would be to have an optimisation
           *       rule that transforms a "forall x :: x in domain(fvf) <==> x in xs"
           *       into a "domain(fvf) == xs".
           */

          Some((lvd.localVar, condition, rcvr, f, gain, forall, fa))

        case _ => None
      }
  }

  /* TODO: This is only defined in the object (instead of in the class) because the class
   *       takes the usual type parameters, which would then have to be added to the
   *       DefaultFieldValueFunctionsEmitter, which is where `fieldAccesses` is used
   *       as well. Not a very convincing reason.
   */
  def fieldAccesses(q: ast.Forall) =
    utility.Visitor.deepCollect(q :: Nil, utility.Nodes.subnodes) {
      case fa: ast.FieldAccess => fa.field
    }
}
