package org.opencypher.spark.prototype.impl.convert

import cats.implicits._
import org.atnos.eff._
import org.atnos.eff.all._
import org.neo4j.cypher.internal.frontend.v3_2.ast.Statement
import org.neo4j.cypher.internal.frontend.v3_2.{InputPosition, ast}
import org.opencypher.spark.prototype.api.expr.Expr
import org.opencypher.spark.prototype.api.ir._
import org.opencypher.spark.prototype.api.ir.block._
import org.opencypher.spark.prototype.api.ir.global.GlobalsRegistry
import org.opencypher.spark.prototype.api.ir.pattern.{AllGiven, Pattern}
import org.opencypher.spark.prototype.impl.convert.types.{_fails, _hasContext, _}
import org.opencypher.spark.prototype.impl.planner.Stage

object CypherQueryBuilder extends Stage[ast.Statement, CypherQuery[Expr], IRBuilderContext] {

  override def plan(input: Statement)(implicit context: IRBuilderContext): CypherQuery[Expr] =
    buildIROrThrow(input, context)

  private def buildIROrThrow(s: ast.Statement, context: IRBuilderContext): CypherQuery[Expr] =
    buildIR[IRBuilderStack[Option[CypherQuery[Expr]]]](s).run(context) match {
      case Left(error) => throw new IllegalStateException(s"Error during IR construction: $error")
      case Right((Some(q), _)) => q
      case Right((None, _)) => throw new IllegalStateException(s"Failed to construct IR")
    }

  private def buildIR[R: _fails : _hasContext](s: ast.Statement): Eff[R, Option[CypherQuery[Expr]]] =
    s match {
      case ast.Query(_, part) =>
        for {
          context <- get[R, IRBuilderContext]
          query <- {
            val builder = new CypherQueryBuilder(context.queryString, context.globals)

            part match {
              case ast.SingleQuery(clauses) =>
                val steps = clauses.map(builder.add[R]).toVector
                val blocks = EffMonad[R].sequence(steps)
                blocks >> builder.build
            }
          }
        } yield Some(query)

      case x =>
        error(IRBuilderError(s"Statement not yet supported: $x"))(None)
    }
}

class CypherQueryBuilder(query: String, globals: GlobalsRegistry) {

  val exprConverter = new ExpressionConverter(globals)
  val patternConverter = new PatternConverter(globals)

  var firstBlock: Option[BlockRef] = None

  def add[R: _fails : _hasContext](c: ast.Clause): Eff[R, Vector[BlockRef]] = {

    c match {
      case ast.Match(_, pattern, _, astWhere) =>
        for {
          given <- convertPattern(pattern)
          where <- convertWhere(astWhere)
          context <- get[R, IRBuilderContext]
          refs <- {
            val blockRegistry = context.blocks
            val after = blockRegistry.reg.headOption.map(_._1).toSet
            val block = MatchBlock[Expr](after, given, where)
            val (ref, reg) = blockRegistry.register(block)
            put[R, IRBuilderContext](context.copy(blocks = reg)) >> pure[R, Vector[BlockRef]](Vector(ref))
          }
        } yield refs

      case ast.Return(_, ast.ReturnItems(_, items), _, _, _, _) =>
        for {
          fieldExprs <- EffMonad[R].sequence(items.map(convertReturnItem[R]).toVector)
          context <- get[R, IRBuilderContext]
          refs <- {
            val blockRegistry = context.blocks
            val yields = ProjectedFields(fieldExprs.toMap)

            val after = blockRegistry.reg.headOption.map(_._1).toSet
            val projs = ProjectBlock[Expr](after = after, where = AllGiven[Expr](), binds = yields)

            val (ref, reg) = blockRegistry.register(projs)

            //         TODO: Add rewriter and put the above case in With(...)
            //         TODO: Figure out nodes and relationships
            val rItems: Seq[Field] = fieldExprs.map(_._1)
            val returns = ResultBlock[Expr](Set(ref), OrderedFields(rItems), Set.empty, Set.empty)

            val (ref2, reg2) = reg.register(returns)
            put[R, IRBuilderContext](context.copy(blocks = reg2)) >> pure[R, Vector[BlockRef]](Vector(ref, ref2))
          }
        } yield refs

      case x =>
        error(IRBuilderError(s"Clause not yet supported: $x"))(Vector.empty[BlockRef])
    }
  }

  private def convertReturnItem[R: _fails : _hasContext](item: ast.ReturnItem): Eff[R, (Field, Expr)] = item match {

    case ast.AliasedReturnItem(e, v) =>
      for {
        expr <- convertExpr(e)
      } yield {
        Field(v.name)(expr.cypherType) -> expr
      }

    case ast.UnaliasedReturnItem(e, t) =>
      for {
        expr <- convertExpr(e)
      } yield {
        // TODO: should this field be named t?
        Field(expr.toString)(expr.cypherType) -> expr
      }
  }

  private def convertPattern[R: _fails : _hasContext](p: ast.Pattern): Eff[R, Pattern[Expr]] = {
    for {
      context <- get[R, IRBuilderContext]
      _ <- put[R, IRBuilderContext] {
        val pattern = patternConverter.convert(p)
        val patternTypes = pattern.fields.foldLeft(context.knownTypes) {
          case (acc, f) => acc.updated(ast.Variable(f.name)(InputPosition.NONE), f.cypherType)
        }
        context.copy(knownTypes = patternTypes)
      }
    } yield patternConverter.convert(p)
  }

  private def convertExpr[R: _fails : _hasContext](e: ast.Expression): Eff[R, Expr] = {
    for {
      context <- get[R, IRBuilderContext]
    } yield {
      val typings = context.infer(e)
      exprConverter.convert(e)(typings)
    }
  }

  private def convertWhere[R: _fails : _hasContext](where: Option[ast.Where]): Eff[R, AllGiven[Expr]] = where match {
    case Some(ast.Where(expr)) =>
      for {
        predicate <- convertExpr(expr)
      } yield {
        predicate match {
          case org.opencypher.spark.prototype.api.expr.Ands(exprs) => AllGiven(exprs)
          case e => AllGiven(Set(e))
        }
      }

    case None =>
      pure[R, AllGiven[Expr]](AllGiven[Expr]())
  }

  def build[R: _fails : _hasContext]: Eff[R, CypherQuery[Expr]] =
    for {
      context <- get[R, IRBuilderContext]
    } yield {
      val blocks = context.blocks
      val (ref, r) = blocks.reg.collectFirst {
        case (_ref, r: ResultBlock[Expr]) => _ref -> r
      }.get

      val model = QueryModel(r, globals, blocks.reg.toMap - ref)
      val info = QueryInfo(query)

      CypherQuery(info, model)
    }
}

