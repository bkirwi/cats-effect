/*
 * Copyright (c) 2017-2021 The Typelevel Cats-effect Project Developers
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

package cats.effect

import cats.effect.laws.util.{TestContext, TestInstances}
import org.scalacheck.Prop
import org.typelevel.discipline.Laws

class BaseTestsSuite extends CatsEffectSuite with TestInstances {

  /** For tests that need a usable [[TestContext]] reference. */
  def testAsync(name: String)(f: TestContext => Unit): Unit =
    test(name)(f(TestContext()))

  def propertyAsync(name: String)(f: TestContext => Prop): Unit =
    property(name)(f(TestContext()))

  def checkAllAsync(name: String, f: TestContext => Laws#RuleSet): Unit = {
    val context = TestContext()
    val ruleSet = f(context)

    for ((id, prop) <- ruleSet.all.properties)
      property(name + "." + id)(prop)
  }
}
