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

package cats
package effect

import cats.effect.laws.discipline.arbitrary._
import cats.kernel.laws.discipline.{MonoidTests, SemigroupTests}
import cats.laws.discipline.ApplicativeTests
import cats.syntax.all._
import org.scalacheck.{Arbitrary, Cogen}

import scala.annotation.nowarn
import scala.concurrent.Promise
import scala.util.Failure

class FiberTests extends BaseTestsSuite {
  implicit def genFiber[A: Arbitrary: Cogen]: Arbitrary[Fiber[IO, A]] =
    Arbitrary(genIO[A].map(io => Fiber(io, IO.unit)))

  @nowarn("msg=never used")
  implicit def fiberEq[F[_]: Applicative, A](implicit FA: Eq[F[A]]): Eq[Fiber[F, A]] =
    Eq.by[Fiber[F, A], F[A]](_.join)

  checkAllAsync("Fiber[IO, *]", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    ApplicativeTests[Fiber[IO, *]].applicative[Int, Int, Int]
  })

  checkAllAsync("Fiber[IO, *]", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    SemigroupTests[Fiber[IO, Int]](Fiber.fiberSemigroup[IO, Int]).semigroup
  })

  checkAllAsync("Fiber[IO, *]", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    MonoidTests[Fiber[IO, Int]].monoid
  })

  testAsync("Canceling join does not cancel the source fiber") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    var fiberCanceled = false
    var joinCanceled = false
    val fiberFinalisersInstalled = Promise[Unit]()
    val joinFinalisersInstalled = Promise[Unit]()

    def waitUnlessInterrupted = IO.cancelable[Unit](_ => IO { fiberCanceled = true })
    def wait(p: Promise[Unit]) = IO.fromFuture(IO.pure(p.future))
    def signal(p: Promise[Unit]) = IO(p.success(()))

    val fa = for {
      fiber <- {
        signal(fiberFinalisersInstalled) *>
          waitUnlessInterrupted
      }.start
      joinFiber <- {
        wait(fiberFinalisersInstalled) *>
          signal(joinFinalisersInstalled) *>
          fiber.join.guaranteeCase {
            case ExitCase.Canceled => IO { joinCanceled = true }
            case _                 => IO.unit
          }
      }.start
      _ <- wait(joinFinalisersInstalled) *> joinFiber.cancel
    } yield ()

    fa.unsafeToFuture()
    ec.tick()

    assertEquals(joinCanceled, true)
    assertEquals(fiberCanceled, false)
  }

  testAsync("Applicative[Fiber[IO, *].map2 preserves both cancelation tokens") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    var canceled = 0

    // Needs latches due to IO being auto-cancelable at async boundaries
    val latch1 = Promise[Unit]()
    val io1 = IO.cancelable[Int] { _ =>
      latch1.success(()); IO(canceled += 1)
    }
    val latch2 = Promise[Unit]()
    val io2 = IO.cancelable[Int] { _ =>
      latch2.success(()); IO(canceled += 1)
    }

    val f: IO[Unit] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- IO.fromFuture(IO.pure(latch1.future))
        _ <- IO.fromFuture(IO.pure(latch2.future))
        _ <- fiber1.map2(fiber2)(_ + _).cancel
      } yield { fiber2.join; () }

    f.unsafeToFuture()
    ec.tick()
    assertEquals(canceled, 2)
  }

  testAsync("Applicative[Fiber[IO, *].map2 cancels first, when second terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    // Needs latch due to auto-cancellation behavior
    val latch = Promise[Unit]()
    val io1 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val io: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- IO.fromFuture(IO.pure(latch.future))
        io <- fiber1.map2(fiber2)(_ + _).join
      } yield io

    val f = io.unsafeToFuture()
    ec.tick()
    assertEquals(f.value, Some(Failure(dummy)))
    assertEquals(wasCanceled, true)
  }

  testAsync("Applicative[Fiber[IO, *].map2 cancels second, when first terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    // Needs latch due to auto-cancellation behavior
    val latch = Promise[Unit]()
    val io2 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- IO.fromFuture(IO.pure(latch.future))
        io <- fiber1.map2(fiber2)(_ + _).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    assertEquals(wasCanceled, true)
  }

  testAsync("Monoid[Fiber[IO, *].combine cancels first, when second terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    // Needs latch due to auto-cancellation behavior
    val latch = Promise[Unit]()
    val io1 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- IO.fromFuture(IO.pure(latch.future))
        io <- fiber1.combine(fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    assertEquals(wasCanceled, true)
  }

  testAsync("Monoid[Fiber[IO, *].combine cancels second, when first terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    // Needs latch due to auto-cancellation behavior
    val latch = Promise[Unit]()
    val io2 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- IO.fromFuture(IO.pure(latch.future))
        io <- fiber1.combine(fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    assertEquals(wasCanceled, true)
  }

  testAsync("Semigroup[Fiber[IO, *].combine cancels first, when second terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    // Needs latch due to auto-cancellation behavior
    val latch = Promise[Unit]()
    val io1 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- IO.fromFuture(IO.pure(latch.future))
        io <- Fiber.fiberSemigroup[IO, Int].combine(fiber1, fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    assertEquals(wasCanceled, true)
  }

  testAsync("Semigroup[Fiber[IO, *].combine cancels second, when first terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    // Needs latch due to auto-cancellation behavior
    val latch = Promise[Unit]()
    val io2 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- IO.fromFuture(IO.pure(latch.future))
        io <- Fiber.fiberSemigroup[IO, Int].combine(fiber1, fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    assertEquals(wasCanceled, true)
  }
}
