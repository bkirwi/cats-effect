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

import cats.effect.tracing.{IOEvent, IOTrace}

import scala.concurrent.ExecutionContext

class CachedStackTracingTests extends CatsEffectSuite {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def traced[A](io: IO[A]): IO[IOTrace] =
    io.flatMap(_ => IO.trace)

  test("captures map frames") {
    val task = IO.pure(0).map(_ + 1).map(_ + 1)

    for (r <- traced(task).unsafeToFuture()) yield {
      assertEquals(r.captured, 3)
      assertEquals(r.events
                     .collect { case e: IOEvent.StackTrace => e }
                     .filter(_.stackTrace.exists(_.getMethodName == "map"))
                     .length,
                   2)
    }
  }

  test("captures bind frames") {
    val task = IO.pure(0).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      assertEquals(r.captured, 3)
      assertEquals(r.events
                     .collect { case e: IOEvent.StackTrace => e }
                     .filter(_.stackTrace.exists(_.getMethodName == "flatMap"))
                     .length,
                   3) // extra one is to capture the trace
    }
  }

  test("captures async frames") {
    val task = IO.async[Int](_(Right(0))).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      assertEquals(r.captured, 4)
      assertEquals(r.events
                     .collect { case e: IOEvent.StackTrace => e }
                     .filter(_.stackTrace.exists(_.getMethodName == "async"))
                     .length,
                   1)
    }
  }

  test("captures bracket frames") {
    val task = IO.unit.bracket(_ => IO.pure(10))(_ => IO.unit).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      assertEquals(r.captured, 6)
      assertEquals(r.events
                     .collect { case e: IOEvent.StackTrace => e }
                     .filter(_.stackTrace.exists(_.getMethodName == "bracket"))
                     .length,
                   1)
    }
  }

  test("captures bracketCase frames") {
    val task = IO.unit.bracketCase(_ => IO.pure(10))((_, _) => IO.unit).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      assertEquals(r.captured, 6)
      assertEquals(r.events
                     .collect { case e: IOEvent.StackTrace => e }
                     .filter(_.stackTrace.exists(_.getMethodName == "bracketCase"))
                     .length,
                   1)
    }
  }
}
