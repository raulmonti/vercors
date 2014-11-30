package viper
package silicon
package supporters

import com.weiglewilczek.slf4s.Logging
import silver.verifier.PartialVerificationError
import silver.verifier.errors.Internal
import silver.verifier.reasons.{InsufficientPermission, MagicWandChunkNotFound}
import interfaces.{Evaluator, Producer, Consumer, Executor, VerificationResult, Failure, Success}
import interfaces.state.{Chunk, State, PathConditions, Heap, Store}
import state.{MagicWandChunk, DirectPredicateChunk, DefaultContext}
import state.terms._

trait HeuristicsSupporter[ST <: Store[ST],
                        H <: Heap[H],
                        PC <: PathConditions[PC],
                        S <: State[ST, H, S]]
    { this:      Logging
            with Evaluator[ST, H, S, DefaultContext[H]]
            with Producer[ST, H, S, DefaultContext[H]]
            with Consumer[Chunk, ST, H, S, DefaultContext[H]]
            with Executor[ST, H, S, DefaultContext[H]]
            with MagicWandSupporter[ST, H, PC, S] =>

  protected val config: Config

  object heuristicsSupporter {
    private type C = DefaultContext[H]
    private type CH = Chunk

    @inline
    def tryOperation[O]
                    (description: String)
                    (σ: S, h: H, c: C)
                    (action: (S, H, C, O => VerificationResult) => VerificationResult)
                    (Q: O => VerificationResult)
                    : VerificationResult = {

      tryWithReactions(description)(σ, h, c)(action, None, 1)(Q)
    }

    def tryWithReactions[O]
                        (description: String)
                        (σ: S, h: H, c: C)
                        (action: (S, H, C, O => VerificationResult) => VerificationResult,
                         initialFailure: Option[Failure[ST, H, S]],
                         depth: Int)
                        (Q: O => VerificationResult)
                        : VerificationResult = {

      var localActionSuccess = false

//      println(s"\n[tryWithReactions]")
//      println(s" depth = $depth")
//      println(s" description = $description")

      val globalActionResult =
        action(σ, h, c, output => {
//          println(s"  action succeeded locally")
          localActionSuccess = true
          Q(output)})

//      println(s"  globalActionResult ($depth, $description) = $globalActionResult")
//      println(s"  localActionSuccess = $localActionSuccess")

      var reactionResult: VerificationResult = globalActionResult
        /* A bit hacky, but having an initial result here simplifies things quite a bit */

        globalActionResult match {
          case _ if localActionSuccess || globalActionResult == Success() =>
            return globalActionResult

          case actionFailure: Failure[ST, H, S] =>
            if (c.applyHeuristics && depth <= config.maxHeuristicsDepth()) {
              var remainingReactions = generateReactions(σ, h, c, actionFailure)
              var triedReactions = 0

              while (reactionResult != Success() && remainingReactions.nonEmpty) {
//                println(s"  trying next reaction (${triedReactions + 1} out of ${triedReactions + remainingReactions.length}})")

                reactionResult = remainingReactions.head.apply(σ, h, c)((σ1, h1, c1) =>
                  tryWithReactions(description)(σ1, h1, c1)(action, initialFailure.orElse(Some(actionFailure)), depth + 1)(Q))

//                println(s"  returned from reaction ${triedReactions + 1} (out of ${triedReactions + remainingReactions.length}})")

                triedReactions += 1

                remainingReactions = remainingReactions.tail
              }
            }
        }

        reactionResult match {
          case Success() =>
            reactionResult

          case reactionFailure: Failure[ST, H, S] =>
            initialFailure.getOrElse(globalActionResult)
        }
    }

    def generateReactions(σ: S, h: H, c: C, cause: Failure[ST, H, S])
                         : Seq[(S, H, C) => ((S, H, C) => VerificationResult) => VerificationResult] = {

      /* HS1: Apply/unfold if wand/pred containing missing wand or acc
       * HS2: package/fold missing wand/pred
       * HS3: Apply/unfold all other wands/preds
       */

      val pve = Internal(ast.True()())

      cause.message.reason match {
        case reason: MagicWandChunkNotFound =>
          /* HS1 (wands) */
          val wand = reason.offendingNode
          val structureMatcher = matchers.structure(wand, c.program)
          val wands = wandInstancesMatching(σ, h, c, structureMatcher)
          val applyWandReactions = wands map (wand => applyWand(wand, pve) _)

          /* HS2 */
          val packageReaction = packageWand(wand, pve) _

          applyWandReactions ++ Seq(packageReaction)

        case reason: InsufficientPermission =>
          val locationMatcher = matchers.location(reason.offendingNode.loc(c.program), c.program)

          /* HS1 (wands) */
          val wands = wandInstancesMatching(σ, h, c, locationMatcher)
          val applyWandReactions = wands map (wand => applyWand(wand, pve) _)

          /* HS1 (predicates) */
          val predicates = predicateInstancesMatching(σ, h, c, locationMatcher)
          val unfoldPredicateReactions = predicates map (predicate => unfoldPredicate(predicate, pve) _)

          /* HS2 (predicates) */
          val foldPredicateReaction =
            reason.offendingNode match {
              case pa: ast.PredicateAccess =>
                val acc = ast.PredicateAccessPredicate(pa, ast.FullPerm()())()
                Some(foldPredicate(acc, pve) _)

              case _ => None
            }

          applyWandReactions ++ unfoldPredicateReactions ++ foldPredicateReaction.toSeq

        case _ => Nil
      }
    }

    /* Heuristics */

    def packageWand(wand: ast.MagicWand, pve: PartialVerificationError)
                   (σ: S, h: H, c: C)
                   (Q: (S, H, C) => VerificationResult)
                   : VerificationResult = {

      val p = FullPerm()

      if (c.exhaleExt) {
//        println(s"  reaction: packaging $wand")
        val packagingExp = ast.Packaging(wand, ast.True()())()
        consume(σ \ h, p, packagingExp, pve, c)((σ2, _, _, c2) => {
          Q(σ2, σ2.h, c2)})
      } else {
//        println(s"  reaction: package $wand")
        val packageStmt = ast.Package(wand)()
        exec(σ \ h, packageStmt, c)((σ1, c1) => {
          Q(σ1, σ1.h, c1)})
      }
    }

    def applyWand(wand: ast.MagicWand, pve: PartialVerificationError)
                 (σ: S, h: H, c: C)
                 (Q: (S, H, C) => VerificationResult)
                 : VerificationResult = {

      if (c.exhaleExt) {
//        println(s"  reaction: applying $wand")
        val lhsAndWand = ast.And(wand.left, wand)()
        magicWandSupporter.applyingWand(σ \ h, wand, lhsAndWand, pve, c)(Q)
      } else {
//        println(s"  reaction: apply $wand")
        val applyStmt = ast.Apply(wand)()
        exec(σ \ h, applyStmt, c)((σ1, c1) => {
          Q(σ1, σ1.h, c1)})
      }
    }

    def unfoldPredicate(acc: ast.PredicateAccessPredicate, pve: PartialVerificationError)
                       (σ: S, h: H, c: C)
                       (Q: (S, H, C) => VerificationResult)
                       : VerificationResult = {


      if (c.exhaleExt) {
//        println(s"  reaction: unfolding $acc")
        magicWandSupporter.unfoldingPredicate(σ \ h, acc, pve, c)(Q)
      } else {
//        println(s"  reaction: unfold $acc")
        val unfoldStmt = ast.Unfold(acc)()
        exec(σ \ h, unfoldStmt, c)((σ1, c1) => {
          Q(σ1, σ1.h, c1)})
      }
    }

    def foldPredicate(acc: ast.PredicateAccessPredicate, pve: PartialVerificationError)
                     (σ: S, h: H, c: C)
                     (Q: (S, H, C) => VerificationResult)
                     : VerificationResult = {

      if (c.exhaleExt) {
//        println(s"  reaction: folding $acc")
        magicWandSupporter.foldingPredicate(σ \ h, acc, pve, c)(Q)
      } else {
//        println(s"  reaction: fold $acc")
        val foldStmt = ast.Fold(acc)()
        exec(σ \ h, foldStmt, c)((σ1, c1) => {
          Q(σ1, σ1.h, c1)})
      }
    }

      /* Helpers */

    def predicateInstancesMatching(σ: S, h: H, c: C, f: PartialFunction[silver.ast.Node, _]): Seq[ast.PredicateAccessPredicate] = {
      val allChunks = σ.h.values ++ h.values ++ c.reserveHeaps.flatMap(_.values)

      val predicateChunks =
        allChunks.collect {
          case ch: DirectPredicateChunk =>
            val body = c.program.findPredicate(ch.name)

            body.existsDefined(f) match {
              case true => Some(ch)
              case _ => None
            }
        }.flatten


      val predicateAccesses =
        predicateChunks.map {
          case DirectPredicateChunk(name, args, _, _, _) =>
            val reversedArgs: Seq[ast.Expression] =
              args map {
                case True() => ast.True()()
                case False() => ast.False()()
                case IntLiteral(n) => ast.IntegerLiteral(n)()
                case t => σ.γ.values.find(p => p._2 == t).get._1
              }

            ast.PredicateAccessPredicate(ast.PredicateAccess(reversedArgs, c.program.findPredicate(name))(), ast.FullPerm()())()
        }.toSeq

      predicateAccesses
    }

    def wandInstancesMatching(σ: S, h: H, c: C, f: PartialFunction[silver.ast.Node, _]): Seq[ast.MagicWand] = {
      val allChunks = σ.h.values ++ h.values ++ c.reserveHeaps.flatMap(_.values)

      val wands =
        allChunks.collect {
          case ch: MagicWandChunk =>
            ch.ghostFreeWand.right.existsDefined(f) match {
              case true => Some(ch.ghostFreeWand)
              case _ => None
            }
        }.flatten.toSeq

      wands
    }

    object matchers {
      def location(loc: ast.Location, program: ast.Program): PartialFunction[silver.ast.Node, Any] = {
        case ast.AccessPredicate(locacc: ast.LocationAccess, _) if locacc.loc(program) == loc =>
      }

      def structure(wand: ast.MagicWand, program: ast.Program): PartialFunction[silver.ast.Node, Any] = {
        case other: ast.MagicWand if wand.structurallyMatches(other, program) =>
      }
    }
  }
}
