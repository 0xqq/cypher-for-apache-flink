/*
 * Copyright (c) 2016-2019 "Neo4j Sweden, AB" [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
package org.opencypher.spark.testing.api.io

import org.opencypher.okapi.api.graph.{GraphName, PropertyGraph}
import org.opencypher.okapi.impl.exception.UnsupportedOperationException
import org.opencypher.okapi.relational.api.graph.RelationalCypherGraph
import org.opencypher.okapi.relational.api.tagging.Tags._
import org.opencypher.okapi.testing.Cypher10Acceptance
import org.opencypher.spark.api.CAPSSession
import org.opencypher.spark.api.CAPSSession._
import org.opencypher.spark.api.value.{CAPSNode, CAPSRelationship}
import org.opencypher.spark.impl.CAPSConverters._
import org.opencypher.spark.impl.encoders._
import org.opencypher.spark.impl.table.SparkTable.DataFrameTable
import org.opencypher.spark.testing.CAPSTestSuite

import scala.util.{Failure, Success, Try}

trait CAPSPGDSAcceptance extends Cypher10Acceptance[CAPSSession, PropertyGraph] {
  self: CAPSTestSuite =>

  override def initSession: CAPSSession = caps

  override def allScenarios: List[Cypher10Scenario] = super.allScenarios ++ tagScenarios

  lazy val tagScenarios = List(

    Cypher10Scenario("Store graphs with tags") { implicit ctx: TestContext =>
      registerPgdsStep(ns)
      session.cypher("CATALOG CREATE GRAPH g1 { CONSTRUCT CREATE ()-[:FOO]->() RETURN GRAPH }")
      session.cypher("CATALOG CREATE GRAPH g2 { CONSTRUCT CREATE () RETURN GRAPH }")

      Try(session.cypher(s"CATALOG CREATE GRAPH $ns.g3 { CONSTRUCT ON g1, g2 RETURN GRAPH }")) match {
        case Failure(_: UnsupportedOperationException) =>
        case Failure(t) => throw t
        case Success(_) =>
          val graph = session.cypher(s"FROM GRAPH $ns.g3 RETURN GRAPH").graph.asCaps

          withClue("tags should be restored correctly") {
            graph.tags should equal(Set(0, 1))
          }

          verify(graph, 3, 1)
      }
    },

    Cypher10Scenario("API: Store graphs with tags") { implicit ctx: TestContext =>
      registerPgdsStep(ns)
      session.cypher("CATALOG CREATE GRAPH g1 { CONSTRUCT CREATE ()-[:FOO]->() RETURN GRAPH }")
      session.cypher("CATALOG CREATE GRAPH g2 { CONSTRUCT CREATE () RETURN GRAPH }")

      val graphToStore = session.cypher("CONSTRUCT ON g1, g2 RETURN GRAPH").graph.asCaps

      val name = GraphName("g3")

      Try(session.catalog.source(ns).store(name, graphToStore)) match {
        case Failure(_: UnsupportedOperationException) =>
        case Failure(t) => throw t
        case Success(_) =>
          val graph = session.catalog.source(ns).graph(name).asCaps

          withClue("tags should be restored correctly") {
            graph.tags should equal(graphToStore.tags)
            graph.tags should equal(Set(0, 1))
          }

          verify(graph, 3, 1)
      }
    }
  )

  private def verify(graph: RelationalCypherGraph[DataFrameTable], expectedNodeSize: Int, expectedRelSize: Int): Unit = {
    val nodes = graph.nodes("n").asDataset.map(row => row("n").cast[CAPSNode]).collect()
    val rels = graph.relationships("r").asDataset.map(row => row("r").cast[CAPSRelationship]).collect()

    nodes.length shouldBe expectedNodeSize
    rels.length shouldBe expectedRelSize

    val nodeTags = nodes.map(_.id.getTag).toSet
    val relTags = rels.map(_.id.getTag).toSet

    nodeTags.foreach(tag => graph.tags should contain(tag))
    relTags.foreach(tag => graph.tags should contain(tag))

    graph.tags -- (nodeTags ++ relTags) shouldBe empty
  }

}
