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
package org.opencypher.morpheus.impl.acceptance

import org.opencypher.morpheus.impl.MorpheusConverters._
import org.opencypher.morpheus.testing.MorpheusTestSuite
import org.opencypher.okapi.api.graph.CypherResult
import org.opencypher.okapi.relational.impl.operators.Cache

class CacheTests extends MorpheusTestSuite with ScanGraphInit {

  describe("scan caching") {

    it("caches a reused scan") {
      val g = initGraph("""CREATE (p:Person {firstName: "Alice", lastName: "Foo"})""")
      val result: CypherResult = g.cypher(
        """
          |MATCH (n: Person)
          |MATCH (m: Person)
          |WHERE n.name = m.name
          |RETURN n.name
        """.stripMargin)

      result.asMorpheus.plans.relationalPlan.get.collect { case c: Cache[_] => c } should have size 2
    }

    it("caches all-node/relationship scans") {
      val g = initGraph(
        """
          |CREATE (a:Person {firstName: "Alice"})
          |CREATE (b:Person {firstName: "Bob"})
          |CREATE (c:Person {firstName: "Carol"})
          |CREATE (book:Book {title: "1984"})
          |CREATE (publisher:Publisher {name : "Orwell Publishing"})
          |CREATE (a)-[:READS]->(book)
          |CREATE (book)-[:PUBLISHED_BY]->(publisher)
        """.stripMargin)

      val result: CypherResult = g.cypher(
        """
          |MATCH (a)-->(b)-->(c)
          |RETURN a, b
        """.stripMargin)

      result.asMorpheus.plans.relationalPlan.get.collect { case c: Cache[_] => c } should have size 5
    }

    it("caches all-node/relationship scans across MATCH statements") {
      val g = initGraph(
        """
          |CREATE (a:Person {firstName: "Alice"})
          |CREATE (b:Person {firstName: "Bob"})
          |CREATE (c:Person {firstName: "Carol"})
          |CREATE (book:Book {title: "1984"})
          |CREATE (publisher:Publisher {name : "Orwell Publishing"})
          |CREATE (a)-[:READS]->(book)
          |CREATE (book)-[:PUBLISHED_BY]->(publisher)
        """.stripMargin)

      val result: CypherResult = g.cypher(
        """
          |MATCH (a)-->(b)
          |MATCH (b)-->(c)
          |RETURN a, b
        """.stripMargin)

      result.asMorpheus.plans.relationalPlan.get.collect { case c: Cache[_] => c } should have size 5
    }
  }
}
