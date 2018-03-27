package org.opencypher.flink.physical.operators

import org.apache.flink.api.common.typeinfo.{BasicTypeInfo, TypeInformation, Types}
import org.apache.flink.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.table.expressions.{Expression, UnresolvedFieldReference}
import org.apache.flink.types.Row
import org.opencypher.flink.FlinkSQLExprMapper._
import org.opencypher.flink.FlinkUtils._
import org.opencypher.flink.TableOps._
import org.opencypher.flink.physical.{CAPFPhysicalResult, CAPFRuntimeContext}
import org.opencypher.flink.physical.operators.CAPFPhysicalOperator._
import org.opencypher.flink.schema.EntityTable._
import org.opencypher.flink.{CAPFRecords, CAPFSession, ColumnName}
import org.opencypher.okapi.api.graph.QualifiedGraphName
import org.opencypher.okapi.api.types._
import org.opencypher.okapi.api.value.CypherValue.CypherList
import org.opencypher.okapi.impl.exception.{IllegalArgumentException, IllegalStateException, NotImplementedException}
import org.opencypher.okapi.ir.api.block.{Asc, Desc, SortItem}
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.ir.impl.syntax.ExprSyntax._
import org.opencypher.okapi.logical.impl._
import org.opencypher.okapi.relational.impl.table._

private[flink] abstract class UnaryPhysicalOperator extends CAPFPhysicalOperator {

  def in: CAPFPhysicalOperator

  override def execute(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = executeUnary(in.execute)

  def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult
}

final case class Cache(in: CAPFPhysicalOperator) extends UnaryPhysicalOperator with InheritedHeader {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    context.cache.getOrElse(in, {
      prev.records.cache()
      context.cache(in) = prev
      prev
    })
  }
}

final case class Scan(in: CAPFPhysicalOperator, inGraph: LogicalGraph, v: Var, header: RecordHeader)
  extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    val graphs = prev.graphs
    val graph = graphs(inGraph.name)
    val records = v.cypherType match {
      case r: CTRelationship =>
        graph.relationships(v.name, r)
      case n: CTNode =>
        graph.nodes(v.name, n)
      case x =>
        throw IllegalArgumentException("an entity type", x)
    }
    assert(header == records.header)
    CAPFPhysicalResult(records, graphs)
  }

}

final case class Alias(in: CAPFPhysicalOperator, expr: Expr, alias: Var, header: RecordHeader)
  extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      val oldSlot = records.header.slotsFor(expr).head

      val newSlot = header.slotsFor(alias).head

      val oldColumnName = ColumnName.of(oldSlot)
      val newColumnName = ColumnName.of(newSlot)

      val newData = if (records.data.columns.contains(oldColumnName)) {
        records.data.safeRenameColumn(oldColumnName, newColumnName)
      } else {
        throw IllegalArgumentException(s"a column with name $oldColumnName")
      }

      CAPFRecords.verifyAndCreate(header, newData)(records.capf)
    }
  }
}

final case class SelectFields(in: CAPFPhysicalOperator, field: IndexedSeq[Var], header: RecordHeader)
  extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      val fieldIndices = field.zipWithIndex.toMap

      val groupedSlots = header.slots.sortBy {
        _.content match {
          case content: FieldSlotContent =>
            fieldIndices.getOrElse(content.field, Int.MaxValue)
          case content@ProjectedExpr(expr) =>
            val deps = expr.dependencies
            deps.headOption
              .filter(_ => deps.size == 1)
              .flatMap(fieldIndices.get)
              .getOrElse(Int.MaxValue)
        }
      }

      val data = records.data
      val columns = groupedSlots.map { s =>
        UnresolvedFieldReference(ColumnName.of(s))
      }
      val newData = records.data.select(columns: _*)

      CAPFRecords.verifyAndCreate(header, newData)(records.capf)
    }
  }
}

final case class SelectGraphs(in: CAPFPhysicalOperator, graphs: Set[String])
  extends UnaryPhysicalOperator with InheritedHeader {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult =
    prev.selectGraphs(graphs)

}

final case class EmptyRecords(in: CAPFPhysicalOperator, header: RecordHeader)(implicit capf: CAPFSession)
  extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult =
    prev.mapRecordsWithDetails(_ => CAPFRecords.empty(header))

}

final case class SetSourceGraph(in: CAPFPhysicalOperator, graph: LogicalExternalGraph) extends UnaryPhysicalOperator with InheritedHeader {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult =
    prev.withGraph(graph.name -> resolve(graph.qualifiedGraphName))
}

final case class RemoveAliases(
  in: CAPFPhysicalOperator,
  dependentFields: Set[(ProjectedField, ProjectedExpr)],
  header: RecordHeader) extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      val renamed = dependentFields.foldLeft(records.data) {
        case (table, (v, expr)) =>
          table.safeRenameColumn(ColumnName.of(v), ColumnName.of(expr))
      }

      CAPFRecords.verifyAndCreate(header, renamed)(records.capf)
    }
  }
}

final case class Filter(in: CAPFPhysicalOperator, expr: Expr, header: RecordHeader) extends UnaryPhysicalOperator {
  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      val filteredRows = records.data.where(expr.asFlinkSQLExpr(header, records.data, context))

      val selectedColumns = header.slots.map { ColumnName.of(_) }.map( UnresolvedFieldReference(_) )

      val newData = filteredRows.select(selectedColumns: _*)

      CAPFRecords.verifyAndCreate(header, newData)(records.capf)
    }
  }
}

final case class Project(in: CAPFPhysicalOperator, expr: Expr, header: RecordHeader) extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      val headerNames = header.slotsFor(expr).map(ColumnName.of)
      val dataNames = records.data.columns.toSeq

      val newData = headerNames.diff(dataNames) match {
        case Seq(one) =>
          val newCol: Expression = expr.asFlinkSQLExpr(header, records.data, context).as(Symbol(one))
          val columnsToSelect = records.data.columns.map(UnresolvedFieldReference(_)) :+ newCol // TODO: missmatch between expression and column/table

          records.data.select(columnsToSelect: _*)
        case seq if seq.isEmpty => throw IllegalStateException(s"Did not find a slot for expression $expr in $headerNames")
        case seq => throw IllegalStateException(s"Got multiple slots for expression $expr: $seq")
      }

      CAPFRecords.verifyAndCreate(header, newData)(records.capf)
    }
  }
}

final case class ProjectExternalGraph(in: CAPFPhysicalOperator, name: String, qualifiedGraphName: QualifiedGraphName) extends UnaryPhysicalOperator with InheritedHeader {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult =
    prev.withGraph(name -> resolve(qualifiedGraphName))
}

final case class Aggregate(
  in: CAPFPhysicalOperator,
  aggregations: Set[(Var, Aggregator)],
  group: Set[Var],
  header: RecordHeader)
  extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      val inData = records.data

      def withInnerExpr(expr: Expr)(f: Expression => Expression) =
        f(expr.asFlinkSQLExpr(records.header, inData, context))

      val ds = inData.toDataSet[Row]

      val columns =
        if (group.nonEmpty) {
          group.flatMap { expr =>
            val withChildren = records.header.selfWithChildren(expr).map(_.content)
            withChildren.map(e => UnresolvedFieldReference(ColumnName.of(e)))
//            withChildren.map(e => withInnerExpr(e)(identity))
          }
        } else null

      val data =
        if (columns != null) {
          Left(inData.groupBy(columns.toSeq: _*))
        } else Right(inData)

      val sum = data.left.get.select(s"n, sum(____n_dot_ageINTEGER)").toDataSet[Row]

      val flinkAggFunctions = aggregations.map {
        case (to, inner) =>
          val columnName = Symbol(ColumnName.from(to.name))
          inner match {
            case Avg(expr) =>
              withInnerExpr(expr)(
                _.avg
                  .cast(toFlinkType(to.cypherType))
                  .as(columnName)
              )

            case Count(expr, distinct) => withInnerExpr(expr)( column =>
              column.count
                .as(columnName)
            )

            case Max(expr) =>
              withInnerExpr(expr)(_.max.as(columnName))

            case Min(expr) =>
              withInnerExpr(expr)(_.min.as(columnName))

            case Sum(expr) =>
              withInnerExpr(expr)(_.sum.as(columnName))

            case Collect(expr, distinct) => ???
            case x =>
              throw NotImplementedException(s"Aggregation function $x")

          }
      }

      val aggregated = data.fold(
        _.select((columns ++ flinkAggFunctions).toSeq: _*),
        _.select(flinkAggFunctions.toSeq: _*)
      )

      CAPFRecords.verifyAndCreate(header, aggregated)(records.capf)
    }
  }
}

final case class Distinct(in: CAPFPhysicalOperator, fields: Set[Var])
  extends UnaryPhysicalOperator with InheritedHeader {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      CAPFRecords.verifyAndCreate(prev.records.header, records.data.distinct())(records.capf)
    }
  }

}

final case class OrderBy(in: CAPFPhysicalOperator, sortItems: Seq[SortItem[Expr]])
  extends UnaryPhysicalOperator with InheritedHeader {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    val getColumnName = (expr: Var) => ColumnName.of(prev.records.header.slotFor(expr))

    prev.mapRecordsWithDetails { records =>

      val sortExpression = sortItems.map {
        case Asc(expr: Var) => expr.asFlinkSQLExpr(records.header, records.data, context)
        case Desc(expr: Var) => expr.asFlinkSQLExpr(records.header, records.data, context)
        case other => throw IllegalArgumentException("ASC or DESC", other)
      }

      val sortedData = records.toTable().orderBy(sortExpression: _*)
      CAPFRecords.verifyAndCreate(header, sortedData)(records.capf)
    }
  }
}

final case class Unwind(in: CAPFPhysicalOperator, list: Expr, item: Var, header: RecordHeader)
  extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    prev.mapRecordsWithDetails { records =>
      val itemColumn = ColumnName.of(header.slotFor(item))
      val newData = list match {
        case Param(name) =>
          context.parameters(name).as[CypherList] match {
            case Some(l) =>
              val flinkType = toFlinkType(item.cypherType)
              val nullable = item.cypherType.isNullable

              val rowList = l.unwrap.map(java.lang.String.valueOf(_))
              val rowDataSet = records.capf.env.fromCollection(rowList)
              val tableSchema = UnresolvedFieldReference(itemColumn)
              val table = records.capf.tableEnv.fromDataSet(rowDataSet, tableSchema)
              val tableWithCorrectType = table.select(tableSchema)
//              records.data.toDataSet[Row].cross(tableWithCorrectType).map {
//                case (r1: Row, r2: Row) =>
//                  r1.
//              } .toTable(records.capf.tableEnv)
              records.data.leftOuterJoin(tableWithCorrectType)
//              TODO

            case None =>
              throw IllegalArgumentException("a list", list)
          }

        case expr =>
          val listColumn = expr.asFlinkSQLExpr(records.header, records.data, context)

          records.data.safeAddColumn(itemColumn, listColumn.flatten())
      }

      CAPFRecords.verifyAndCreate(header, newData)(records.capf)
    }
  }
}

final case class InitVarExpand(in: CAPFPhysicalOperator, source: Var, edgeList: Var, target: Var, header: RecordHeader)
  extends UnaryPhysicalOperator {

  override def executeUnary(prev: CAPFPhysicalResult)(implicit context: CAPFRuntimeContext): CAPFPhysicalResult = {
    val sourceSlot = header.slotFor(source)
    val edgeListSlot = header. slotFor(edgeList)
    val targetSlot = header.slotFor(target)

    assertIsNode(targetSlot)

    prev.mapRecordsWithDetails { records =>
      val inputData = records.data
      val keep = inputData.columns.map(inputData.col)

      val edgeListColName = columnName(edgeListSlot)

      ???
    }
  }
}

