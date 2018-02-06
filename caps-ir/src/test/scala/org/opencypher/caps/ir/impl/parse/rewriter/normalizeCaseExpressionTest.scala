/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
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
 */
package org.opencypher.caps.ir.impl.parse.rewriter

import org.neo4j.cypher.internal.compiler.v3_4.SyntaxExceptionCreator
import org.neo4j.cypher.internal.util.v3_4.InputPosition
import org.opencypher.caps.ir.test.support.RewriterTestSupport
import org.opencypher.caps.test.BaseTestSuite

class normalizeCaseExpressionTest extends BaseTestSuite with RewriterTestSupport {
  override val rewriter = normalizeCaseExpression(new SyntaxExceptionCreator("<Query>", Some(InputPosition.NONE)))

  it("should rewrite simple CASE statement") {
    assertRewrite(
      """
        |MATCH (n)
        |RETURN
        |  CASE n.val
        |    WHEN "foo" THEN 1
        |    WHEN "bar" THEN 2
        |  END AS val
      """.stripMargin,
      """
        |MATCH (n)
        |RETURN
        |  CASE
        |    WHEN n.val = "foo" THEN 1
        |    WHEN n.val = "bar" THEN 2
        |  END AS val
      """.stripMargin)
  }

  it("should rewrite simple CASE statement with default") {
    assertRewrite(
      """
        |MATCH (n)
        |RETURN
        |  CASE n.val
        |    WHEN "foo" THEN 1
        |    WHEN "bar" THEN 2
        |    ELSE 3
        |  END AS val
      """.stripMargin,
      """
        |MATCH (n)
        |RETURN
        |  CASE
        |    WHEN n.val = "foo" THEN 1
        |    WHEN n.val = "bar" THEN 2
        |    ELSE 3
        |  END AS val
      """.stripMargin)
  }

  it("should not rewrite generic CASE statement") {
    assertRewrite(
      """
        |MATCH (n)
        |RETURN
        |  CASE
        |    WHEN n.val = "foo" THEN 1
        |    WHEN n.val = "bar" THEN 2
        |    ELSE 3
        |  END AS val
      """.stripMargin,
      """
        |MATCH (n)
        |RETURN
        |  CASE
        |    WHEN n.val = "foo" THEN 1
        |    WHEN n.val = "bar" THEN 2
        |    ELSE 3
        |  END AS val
      """.stripMargin)
  }
}
