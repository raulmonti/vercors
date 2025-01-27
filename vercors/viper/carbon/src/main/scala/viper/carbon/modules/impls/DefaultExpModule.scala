/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.carbon.modules.impls

import viper.carbon.modules.{ExpModule, StatelessComponent}
import viper.silver.{ast => sil}
import viper.carbon.boogie._
import viper.carbon.verifier.Verifier
import viper.silver.verifier.{PartialVerificationError, reasons}
import viper.carbon.boogie.Implicits._
import viper.carbon.modules.components.DefinednessComponent
import viper.silver.ast.{LocationAccess, MagicWand, PredicateAccess, Ref}
import viper.silver.ast.utility.Expressions

/**
 * The default implementation of [[viper.carbon.modules.ExpModule]].
 */
class DefaultExpModule(val verifier: Verifier) extends ExpModule with DefinednessComponent with StatelessComponent {

  import verifier._
  import heapModule._
  import mainModule._
  import domainModule._
  import seqModule._
  import setModule._
  import permModule._
  import inhaleModule._
  import funcPredModule._
  import exhaleModule._
  import stateModule._

  override def start() {
    register(this)
  }

  def name = "Expression module"

  override def translateExpInWand(e: sil.Exp): Exp = {
    val duringPackageStmt = wandModule.nestingDepth > 0
    if(duringPackageStmt){
      val oldCurState = stateModule.state
      stateModule.replaceState(wandModule.UNIONState.asInstanceOf[StateRep].state)  // state in which 'e' is evaluated

      val stmt = translateExp(e)

      stateModule.replaceState(oldCurState)

      stmt
    }else{
      translateExp(e)
    }
  }


  override def translateExp(e: sil.Exp): Exp = {
    e match {
      case sil.IntLit(i) =>
        IntLit(i)
      case sil.BoolLit(b) =>
        BoolLit(b)
      case sil.NullLit() =>
        translateNull
      case l@sil.LocalVar(name) =>
        translateLocalVar(l)
      case r@sil.Result() =>
        translateResult(r)
      case f@sil.FieldAccess(rcv, field) =>
        translateLocationAccess(f)
      case sil.InhaleExhaleExp(a, b) =>
        sys.error("should not occur here (either, we inhale or exhale this expression, in which case whenInhaling/whenExhaling should be used, or the expression is not allowed to occur.")
      case p@sil.PredicateAccess(rcv, predicateName) =>
        translateLocationAccess(p)
      case sil.Unfolding(acc, exp) =>
        translateExp(exp)
      case sil.Applying(wand, exp) => translateExp(exp)
      case sil.Old(exp) =>
        val prevState = stateModule.state
        stateModule.replaceState(stateModule.oldState)
        val res = translateExp(exp)
        stateModule.replaceState(prevState)
        res
      case sil.LabelledOld(exp, oldLabel) =>
        var findLabel = oldLabel
        if(findLabel.equals("lhs"))
          findLabel = findLabel+wandModule.getActiveLhs()
        val prevState = stateModule.state
        stateModule.replaceState(stateModule.stateRepositoryGet(findLabel).get)
        val res = translateExp(exp)
        stateModule.replaceState(prevState)
        res
      case sil.Let(lvardecl, exp, body) =>
        val translatedExp = translateExp(exp) // expression to bind "v" to
      val v = env.makeUniquelyNamed(lvardecl) // choose a fresh "v" binder
        env.define(v.localVar)
        val translatedBody = translateExp(Expressions.instantiateVariables(body, Seq(lvardecl), Seq(v.localVar))) // translate body with "v" in place of bound variable
      val substitutedBody = translatedBody.replace(env.get(v.localVar), translatedExp) // now replace all "v"s with expression. Doing this after translation avoids constructs such as heap-dependant expressions getting reevaluated after substitution in the wrong heaps (e.g. if substituted into an "old" expression).
        env.undefine(v.localVar)
        substitutedBody
      case sil.CondExp(cond, thn, els) =>
        CondExp(translateExp(cond), translateExp(thn), translateExp(els))
      case sil.Exists(vars, exp) => {
        // alpha renaming, to avoid clashes in context
        val renamedVars: Seq[sil.LocalVarDecl] = vars map (v => {
          val v1 = env.makeUniquelyNamed(v); env.define(v1.localVar); v1
        });
        val renaming = (e: sil.Exp) => Expressions.instantiateVariables(e, (vars map (_.localVar)), renamedVars map (_.localVar))
        val res = Exists(renamedVars map translateLocalVarDecl, translateExp(renaming(exp)))
        renamedVars map (v => env.undefine(v.localVar))
        res
      }
      case sil.Forall(vars, triggers, exp) => {
        // alpha renaming, to avoid clashes in context
        val renamedVars: Seq[sil.LocalVarDecl] = vars map (v => {
          val v1 = env.makeUniquelyNamed(v); env.define(v1.localVar); v1
        });
        val renaming = (e: sil.Exp) => Expressions.instantiateVariables(e, (vars map (_.localVar)), renamedVars map (_.localVar))
        val ts : Seq[Trigger] = (triggers map
          (t => (funcPredModule.toExpressionsUsedInTriggers(t.exps map (e => translateExp(renaming(e)))))
            map (Trigger(_)) // build a trigger for each sequence element returned (in general, one original trigger can yield multiple alternative new triggers)
            )).flatten
        val res = Forall(renamedVars map translateLocalVarDecl, ts, translateExp(renaming(exp)))
        renamedVars map (v => env.undefine(v.localVar))
        res
      }
      case sil.ForPerm(variables, accessRes, body) => {

        val variable = variables.head

        if (variables.length != 1) sys.error("Carbon only supports a single quantified variable in forperm, see Carbon issue #243")
        if (variable.typ != Ref) sys.error("Carbon only supports Ref type in forperm, see Carbon issue #243")
        accessRes match {
          case _: MagicWand => sys.error("Carbon does not support magic wands in forperm, see Carbon issue #243")
          case p: PredicateAccess => if (p.loc(program).formalArgs.length != 1) sys.error("Carbon only supports predicates with a single argument in forperm, see Carbon issue #243")
          case _ =>
        }

        val locations = Seq(accessRes.asInstanceOf[LocationAccess].loc(program))

        // alpha renaming, to avoid clashes in context
        val renamedVar: sil.LocalVarDecl = {
          val v1 = env.makeUniquelyNamed(variable); env.define(v1.localVar); v1
        }
        val renaming = (e: sil.Exp) => Expressions.instantiateVariables(e, Seq(variable.localVar), Seq(renamedVar.localVar))
        // val ts = triggers map (t => Trigger(t.exps map {e => verifier.funcPredModule.toTriggers(translateExp(renaming(e)))} // no triggers yet?
        val perLocFilter: sil.Location => (Exp, Trigger) = loc => {
          val locAccess: LocationAccess = loc match {
            case f: sil.Field => sil.FieldAccess(renamedVar.localVar, f)(loc.pos, loc.info)
            case p: sil.Predicate => sil.PredicateAccess(Seq(renamedVar.localVar), p)(loc.pos, loc.info, loc.errT)
          }
          (hasDirectPerm(locAccess), Trigger(permissionLookup(locAccess)))
        }
        val filter = locations.foldLeft[(Exp, Seq[Trigger])](BoolLit(false), Seq())((soFar, loc) => soFar match {
          case (exp, triggers) =>
            perLocFilter(loc) match {
              case (newExp, newTrigger) => (BinExp(exp, Or, newExp), triggers ++ Seq(newTrigger))
            }
        })

        val res = Forall(translateLocalVarDecl(renamedVar), filter._2, // no triggers yet :(
          BinExp(filter._1, Implies, translateExp(renaming(body))))
        env.undefine(renamedVar.localVar)
        res
      }
      case sil.WildcardPerm() =>
        translatePerm(e)
      case sil.FullPerm() =>
        translatePerm(e)
      case sil.NoPerm() =>
        translatePerm(e)
      case sil.EpsilonPerm() =>
        translatePerm(e)
      case sil.PermMinus(_) =>
        translatePerm(e)
      case sil.CurrentPerm(loc) =>
        translatePerm(e)
      case sil.FractionalPerm(left, right) =>
        translatePerm(e)
      case sil.AccessPredicate(loc, perm) =>
        sys.error("not handled by expression module")
      case sil.EqCmp(left, right) =>
        left.typ match {
          case _: sil.SeqType =>
            translateSeqExp(e)
          case _: sil.SetType =>
            translateSetExp(e)
          case _: sil.MultisetType =>
            translateSetExp(e)
          case x if x == sil.Perm =>
            translatePermComparison(e)
          case _ =>
            BinExp(translateExp(left), EqCmp, translateExp(right))
        }
      case sil.NeCmp(left, right) =>
        left.typ match {
          case _: sil.SeqType =>
            translateSeqExp(e)
          case _: sil.SetType =>
            translateSetExp(e)
          case _: sil.MultisetType =>
            translateSetExp(e)
          case x if x == sil.Perm =>
            translatePermComparison(e)
          case _ =>
            BinExp(translateExp(left), NeCmp, translateExp(right))
        }
      case sil.DomainBinExp(_, sil.PermGeOp, _) |
           sil.DomainBinExp(_, sil.PermGtOp, _) |
           sil.DomainBinExp(_, sil.PermLeOp, _) |
           sil.DomainBinExp(_, sil.PermLtOp, _) =>
        translatePermComparison(e)
      case sil.DomainBinExp(_, sil.PermAddOp, _) |
           sil.DomainBinExp(_, sil.PermMulOp, _) |
           sil.DomainBinExp(_, sil.PermSubOp, _) |
           sil.DomainBinExp(_, sil.IntPermMulOp, _) |
           sil.DomainBinExp(_, sil.FracOp, _) |
           sil.DomainBinExp(_, sil.PermDivOp, _) =>
        translatePerm(e)
      case sil.DomainBinExp(left, op, right) =>
        val bop = op match {
          case sil.OrOp => Or
          case sil.LeOp => LeCmp
          case sil.LtOp => LtCmp
          case sil.GeOp => GeCmp
          case sil.GtOp => GtCmp
          case sil.AddOp => Add
          case sil.SubOp => Sub
          case sil.DivOp => IntDiv
          case sil.ModOp => Mod
          case sil.MulOp => Mul
          case sil.AndOp => And
          case sil.ImpliesOp => Implies
          case _ =>
            sys.error("Expression translation did not match any cases (should be handled before reaching translateExp code)" + e.getClass())
        }
        BinExp(translateExp(left), bop, translateExp(right))
      case sil.Minus(exp) =>
        UnExp(Minus, translateExp(exp))
      case sil.Not(exp) =>
        UnExp(Not, translateExp(exp))
      case fa@sil.FuncApp(_, _) =>
        translateFuncApp(fa)
      case fa@sil.DomainFuncApp(_, _, _) =>
        translateDomainFuncApp(fa)

      case seqExp@sil.EmptySeq(elemTyp) =>
        translateSeqExp(seqExp)
      case seqExp@sil.ExplicitSeq(elems) =>
        translateSeqExp(seqExp)
      case seqExp@sil.RangeSeq(low, high) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqAppend(left, right) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqIndex(seq, idx) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqTake(seq, n) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqDrop(seq, n) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqContains(elem, seq) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqUpdate(seq, idx, elem) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqLength(seq) =>
        translateSeqExp(seqExp)

      case setExp@sil.EmptySet(elemTyp) => translateSetExp(setExp)
      case setExp@sil.ExplicitSet(elems) => translateSetExp(setExp)
      case setExp@sil.EmptyMultiset(elemTyp) => translateSetExp(setExp)
      case setExp@sil.ExplicitMultiset(elems) => translateSetExp(setExp)
      case setExp@sil.AnySetUnion(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetIntersection(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetSubset(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetMinus(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetContains(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetCardinality(_) => translateSetExp(setExp)
      case _ => sys.error("Viper expression didn't match any existing case.")
    }
  }

  override def translateLocalVar(l: sil.LocalVar): LocalVar = {
    env.get(l)
  }

  override def simplePartialCheckDefinedness(e: sil.Exp, error: PartialVerificationError, makeChecks: Boolean): Stmt = {

    val stmt: Stmt = (if (makeChecks)
      e match {
        case sil.Div(a, b) =>
            Assert(translateExp(b) !== IntLit(0), error.dueTo(reasons.DivisionByZero(b)))
        case sil.Mod(a, b) =>
            Assert(translateExp(b) !== IntLit(0), error.dueTo(reasons.DivisionByZero(b)))
        case sil.FractionalPerm(a, b) =>
            Assert(translateExp(b) !== IntLit(0), error.dueTo(reasons.DivisionByZero(b)))
        case _ => Nil
      }
    else Nil)

    stmt
  }

  override def checkDefinedness(e: sil.Exp, error: PartialVerificationError, makeChecks: Boolean,
                                duringPackageStmt: Boolean = false, ignoreIfInWand: Boolean = false): Stmt = {

    if(duringPackageStmt && ignoreIfInWand)  // ignore the check
      return Statements.EmptyStmt

    val oldCurState = stateModule.state
    if(duringPackageStmt) {
      stateModule.replaceState(wandModule.UNIONState.asInstanceOf[StateRep].state)
    }

    var stmt =
      MaybeCommentBlock(s"Check definedness of $e",
      MaybeStmt(checkDefinednessImpl(e, error, makeChecks = makeChecks),
        if(duringPackageStmt) wandModule.exchangeAssumesWithBoolean(stateModule.assumeGoodState, wandModule.getCurOpsBoolvar()) else stateModule.assumeGoodState))

    if(duringPackageStmt) {
      stateModule.replaceState(oldCurState)
      If(wandModule.getCurOpsBoolvar(), stmt, Statements.EmptyStmt)
    }else stmt
  }

  private def checkDefinednessImpl(e: sil.Exp, error: PartialVerificationError, makeChecks: Boolean): Stmt = {
    e match {
      case sil.And(e1, e2) =>
        checkDefinednessImpl(e1, error, makeChecks = makeChecks) ::
          If(translateExp(Expressions.asBooleanExp(e1)), checkDefinednessImpl(e2, error, makeChecks = makeChecks), Statements.EmptyStmt) ::
          Nil
      case sil.Implies(e1, e2) =>
        checkDefinednessImpl(e1, error, makeChecks = makeChecks) ::
          If(translateExp(e1), checkDefinednessImpl(e2, error, makeChecks = makeChecks), Statements.EmptyStmt) ::
          Nil
      case sil.CondExp(c, e1, e2) =>
        checkDefinednessImpl(c, error, makeChecks = makeChecks) ::
          If(translateExp(c), checkDefinednessImpl(e1, error, makeChecks = makeChecks), checkDefinednessImpl(e2, error, makeChecks = makeChecks)) ::
          Nil
      case sil.Or(e1, e2) =>
        checkDefinednessImpl(e1, error, makeChecks = makeChecks) :: // short-circuiting evaluation:
          If(UnExp(Not, translateExp(e1)), checkDefinednessImpl(e2, error, makeChecks = makeChecks), Statements.EmptyStmt) ::
          Nil
      case w@sil.MagicWand(lhs, rhs) =>
        checkDefinednessWand(w, error, makeChecks = makeChecks)
      case l@sil.Let(v, e, body) =>
        checkDefinednessImpl(e, error, makeChecks = makeChecks) ::
        {
          val u = env.makeUniquelyNamed(v) // choose a fresh "v" binder
          env.define(u.localVar)
          Assign(translateLocalVar(u.localVar),translateExp(e)) ::
          checkDefinednessImpl(body.replace(v.localVar, u.localVar), error, makeChecks = makeChecks) ::
            {
              env.undefine(u.localVar)
              Nil
            }
        }
      case _ =>
        def translate: Seqn = {
          val checks = components map (_.partialCheckDefinedness(e, error, makeChecks = makeChecks))
          val stmt = checks map (_._1())
          // AS: note that some implementations of the definedness checks rely on the order of these calls (i.e. parent nodes are checked before children, and children *are* always checked after parents.
          val stmt2 = for (sub <- e.subnodes if sub.isInstanceOf[sil.Exp]) yield {
            checkDefinednessImpl(sub.asInstanceOf[sil.Exp], error, makeChecks = makeChecks)
          }
          val stmt3 = checks map (_._2())

          e match {
            case sil.MagicWand(lhs, rhs) =>
              sys.error("wand subnodes:" + e.subnodes.toString() +
                "stmt:" + stmt.toString() +
                "stmt2:" + stmt2.toString() +
                "stmt3:" + stmt3.toString())
            case _ => Nil
          }

          stmt ++ stmt2 ++ stmt3 ++
            MaybeCommentBlock("Free assumptions", allFreeAssumptions(e))
        }

        if (e.isInstanceOf[sil.QuantifiedExp]) {
          val bound_vars = e.asInstanceOf[sil.QuantifiedExp].variables
          bound_vars map (v => env.define(v.localVar))
          val res = if (e.isInstanceOf[sil.ForPerm]) {
            val eAsForallRef = e.asInstanceOf[sil.ForPerm]

            if (eAsForallRef.variables.length != 1) sys.error("Carbon only supports a single quantified variable in forperm, see Carbon issue #243")
            if (eAsForallRef.variables.head.typ != Ref) sys.error("Carbon only supports Ref type in forperm, see Carbon issue #243")
            eAsForallRef.resource match {
              case _: MagicWand => sys.error("Carbon does not support magic wands in forperm, see Carbon issue #243")
              case p: PredicateAccess => if (p.loc(program).formalArgs.length != 1) sys.error("Carbon only supports predicates with a single argument in forperm, see Carbon issue #243")
              case _ =>
            }

            val bound_var = eAsForallRef.variables.head
            val perLocFilter: sil.Location => LocationAccess = loc => loc match {
              case f: sil.Field => sil.FieldAccess(bound_var.localVar, f)(loc.pos, loc.info)
              case p: sil.Predicate => sil.PredicateAccess(Seq(bound_var.localVar), p)(loc.pos, loc.info, loc.errT)
            }
            val filter: Exp = hasDirectPerm(eAsForallRef.resource.asInstanceOf[LocationAccess])

            handleQuantifiedLocals(bound_vars, If(filter, translate, Nil))
          } else handleQuantifiedLocals(bound_vars, translate)
          bound_vars map (v => env.undefine(v.localVar))
          res
        } else e match {
          case sil.Old(_) =>
            val prevState = stateModule.state
            stateModule.replaceState(stateModule.oldState)
            val res = translate
            stateModule.replaceState(prevState)
            res
          case sil.LabelledOld(_, oldLabel) =>
            var findLabel = oldLabel
            if(findLabel.equals("lhs"))
              findLabel = "lhs"+wandModule.getActiveLhs()
            val prevState = stateModule.state
            stateModule.replaceState(stateModule.stateRepositoryGet(findLabel).get)
            val res = translate
            stateModule.replaceState(prevState)
            res
          case _ =>
            translate
        }
    }
  }

  /**
    * checks self-framedness of both sides of wand
    * GP: maybe should "MagicWandNotWellFormed" error
    */
  private def checkDefinednessWand(e: sil.MagicWand, error: PartialVerificationError, makeChecks: Boolean): Stmt = {
    val (initStmtLHS, curState): (Stmt, stateModule.StateSnapshot) = stateModule.freshEmptyState("WandDefLHS", true)
    val defStateLHS = stateModule.state
    val (initStmtRHS, _): (Stmt, stateModule.StateSnapshot) = stateModule.freshEmptyState("WandDefRHS", true)
    val defStateRHS = stateModule.state

    stateModule.replaceState(defStateLHS)
    val lhs = initStmtLHS ++ checkDefinednessOfSpecAndInhale(e.left, error)
    val lhsID = wandModule.getNewLhsID() // identifier for the lhs of the wand to be referred to later when 'old(lhs)' is used
    val defineLHS = stmtModule.translateStmt(sil.Label("lhs"+lhsID, Nil)(e.pos, e.info))
    wandModule.pushToActiveWandsStack(lhsID)
    stateModule.replaceState(defStateRHS)
    val rhs = initStmtRHS ++ checkDefinednessOfSpecAndInhale(e.right, error)
    wandModule.popFromActiveWandsStack()
    stateModule.replaceState(curState)
    NondetIf(lhs ++ defineLHS ++ rhs ++ Assume(FalseLit()))
  }


  def handleQuantifiedLocals(vars: Seq[sil.LocalVarDecl], res: Stmt): Stmt = {
    // introduce local variables for the variables in quantifications. we do this by first check
    // definedness without worrying about missing variable declarations, and then replace all of them
    // with fresh variables.
    val namespace = verifier.freshNamespace("exp.quantifier")
    val newVars = vars map (x => (translateLocalVar(x.localVar),
      // we use a fresh namespace to make sure we get fresh variables
      Identifier(x.name)(namespace)
      ))
    Transformer.transform(res, {
      case v@LocalVar(name, _) =>
        newVars.find(x => (name == x._1.name)) match {
          case None => v // no change
          case Some((x, xb)) =>
            // use the new variable
            LocalVar(xb, x.typ)
        }
    })()
  }


  override def checkDefinednessOfSpecAndInhale(e: sil.Exp, error: PartialVerificationError, statesStack: List[Any] = null, duringPackageStmt: Boolean = false): Stmt = {

    val stmt =
    {
      e match {
        case sil.And(e1, e2) =>
          checkDefinednessOfSpecAndInhale(e1, error, statesStack, duringPackageStmt) ::
            checkDefinednessOfSpecAndInhale(e2, error, statesStack, duringPackageStmt) ::
            Nil
        case sil.Implies(e1, e2) =>
          checkDefinedness(e1, error, duringPackageStmt = duringPackageStmt) ++ {
            if (duringPackageStmt)
              If(wandModule.getCurOpsBoolvar() ==> translateExpInWand(e1), checkDefinednessOfSpecAndInhale(e2, error, statesStack, duringPackageStmt), Statements.EmptyStmt)
            else
              If(translateExp(e1), checkDefinednessOfSpecAndInhale(e2, error, statesStack, duringPackageStmt), Statements.EmptyStmt)
          }
        case sil.CondExp(c, e1, e2) =>
          checkDefinedness(c, error, duringPackageStmt = duringPackageStmt) ++ {
            if (duringPackageStmt)
              If(wandModule.getCurOpsBoolvar() ==> translateExpInWand(c), checkDefinednessOfSpecAndInhale(e1, error, statesStack, duringPackageStmt), checkDefinednessOfSpecAndInhale(e2, error, statesStack, duringPackageStmt))
            else
              If(translateExp(c), checkDefinednessOfSpecAndInhale(e1, error, statesStack, duringPackageStmt), checkDefinednessOfSpecAndInhale(e2, error, statesStack, duringPackageStmt))
          }
      case l@sil.Let(v, exp, body) =>
        checkDefinedness(exp, error, true) ::
          {
            val u = env.makeUniquelyNamed(v) // choose a fresh "v" binder
            env.define(u.localVar)
            Assign(translateLocalVar(u.localVar),translateExp(exp)) ::
              checkDefinednessOfSpecAndInhale(body.replace(v.localVar, u.localVar), error) ::
              {
                env.undefine(u.localVar)
                Nil
              }
          }
      //      case fa@sil.Forall(vars, triggers, expr) => // NOTE: there's no need for a special case for QPs, since these are desugared, introducing conjunctions
        case _ =>
          checkDefinedness(e, error, duringPackageStmt = duringPackageStmt) ++
            inhale(e, statesStack, duringPackageStmt)
      }
    }

    if(duringPackageStmt) {
      If(wandModule.getCurOpsBoolvar(), stmt, Statements.EmptyStmt)
    }else stmt
  }

  override def checkDefinednessOfSpecAndExhale(e: sil.Exp, definednessError: PartialVerificationError, exhaleError: PartialVerificationError
                                               , statesStack: List[Any] = null, duringPackageStmt: Boolean = false): Stmt = {

    val stmt =
      e match {
      case sil.And(e1, e2) =>
        checkDefinednessOfSpecAndExhale(e1, definednessError, exhaleError, statesStack, duringPackageStmt) ::
          checkDefinednessOfSpecAndExhale(e2, definednessError, exhaleError, statesStack, duringPackageStmt) ::
          Nil
      case sil.Implies(e1, e2) =>
        checkDefinedness(e1, definednessError) ++
          If(translateExp(e1), checkDefinednessOfSpecAndExhale(e2, definednessError, exhaleError, statesStack, duringPackageStmt), Statements.EmptyStmt)
      case sil.CondExp(c, e1, e2) =>
        checkDefinedness(c, definednessError) ++
          If(translateExp(c),
            checkDefinednessOfSpecAndExhale(e1, definednessError, exhaleError, statesStack, duringPackageStmt),
            checkDefinednessOfSpecAndExhale(e2, definednessError, exhaleError, statesStack, duringPackageStmt))
      case l@sil.Let(v, exp, body) =>
        checkDefinedness(exp, definednessError, true) ::
          {
            val u = env.makeUniquelyNamed(v) // choose a fresh "v" binder
            env.define(u.localVar)
            Assign(translateLocalVar(u.localVar),translateExp(exp)) ::
              checkDefinednessOfSpecAndExhale(body.replace(v.localVar, u.localVar), definednessError, exhaleError) ::
              {
                env.undefine(u.localVar)
                Nil
              }
          }//      case fa@sil.Forall(vars, triggers, expr) => // NOTE: there's no need for a special case for QPs, since these are desugared, introducing conjunctions
      case _ =>
        checkDefinedness(e, definednessError) ++
          exhale(Seq((e, exhaleError)))
    }

    if(duringPackageStmt) {
      If(wandModule.getCurOpsBoolvar(), stmt, Statements.EmptyStmt)
    }else stmt
  }

  override def allFreeAssumptions(e: sil.Exp): Stmt = {
    def translate: Seqn = {
      val stmt = components map (_.freeAssumptions(e))
      /**
       * Generally if e' is a subexpression of e then whenever e is inhaled/exhaled then any assumption that  can be
       * made for free if e' is inhaled/exhaled is also free.
       * If e is a magic wand then this is not true since what is inhaled is just the wand as a complete entity,
       * no assumptions can be made on the left and right hand side or their subexpressions as they contain assertions
       * which are only exhaled/inhaled when the wand is applied.
       */
      val stmt2 =
        e match {
          case sil.MagicWand(_,_) => Nil
          case sil.Forall(_,_,_) => Nil
          case sil.Let(v, e, body) => {
            val u = env.makeUniquelyNamed(v) // choose a fresh "v" binder
            env.define(u.localVar)
            val stmts = Assign(translateLocalVar(u.localVar),translateExp(e)) ++ allFreeAssumptions(body.replace(v.localVar,u.localVar))
            env.undefine(u.localVar)
            stmts
          }
          case _ =>
            for (sub <- e.subnodes if sub.isInstanceOf[sil.Exp]) yield {
              allFreeAssumptions(sub.asInstanceOf[sil.Exp])
            }
        }
      stmt ++ stmt2
    }
    if (e.isInstanceOf[sil.Old]) {
      val prevState = stateModule.state
      stateModule.replaceState(stateModule.oldState)
      val res = translate
      stateModule.replaceState(prevState)
      res
    } else {
      translate
    }
  }
}
