package org.opencypher.spark.impl.types

import org.neo4j.cypher.internal.frontend.v3_2.ast.{Expression, Variable}
import org.opencypher.spark.api.CypherType
import org.opencypher.spark.api.schema.Schema

sealed trait TypingResult {
  def bind(f: TypeContext => TypingResult): TypingResult

  def inferLabels(schema: Schema): TypingResult = ???
}

final case class TypingFailed(errors: Seq[TypingError]) extends TypingResult {
  override def bind(f: TypeContext => TypingResult): TypingResult = this
}

object TypeContext {
  val empty = TypeContext(Map.empty)
}

final case class TypeContext(typeTable: Map[Expression, CypherType]) extends TypingResult {

  override def bind(f: TypeContext => TypingResult): TypingResult = f(this)

  // TODO: Error handling
  def joinType(accType: CypherType, expr: Expression): CypherType = {
    val cypherType = typeTable(expr)
    val foo = accType join cypherType
    foo
  }

  def updateType(update: (Expression, CypherType)): TypeContext = {
    val (k, v) = update
    copy(typeTable.updated(k, v))
  }

  def typeOf(expr: Expression)(f: CypherType => TypingResult): TypingResult = typeTable.get(expr) match {
    case Some(typ) => f(typ)
    case None => TypingFailed(Seq(UntypedExpression(expr)))
  }

  def typeOf(expr1: Expression, expr2: Expression)(f: (CypherType, CypherType) => TypingResult): TypingResult =
    typeTable.get(expr1) match {
      case Some(typ) => typeOf(expr2)(f(typ, _))
      case None => TypingFailed(Seq(UntypedExpression(expr1)))
  }

  def typeOf(expr: Seq[Expression])(f: Seq[CypherType] => TypingResult): TypingResult = {
    val maybeTypes = expr.foldLeft[Either[Expression, Seq[CypherType]]](Right(Seq())) {
      case (Right(types), next) => typeTable.get(next).map { (tpe: CypherType) => types :+ tpe }.toRight(next)
      case (left, _) => left
    }

    maybeTypes match {
      case Right(types) => f(types)
      case Left(missing) => TypingFailed(Seq(UntypedExpression(missing)))
    }
  }
}

