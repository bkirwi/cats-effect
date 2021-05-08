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

import cats.data.Kleisli
import cats.effect.concurrent.Deferred
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.kernel.laws.discipline.MonoidTests
import cats.laws._
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import cats.syntax.all._
import org.scalacheck.Prop.forAll

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration._
import scala.util.Success

class ResourceTests extends BaseTestsSuite {
  checkAllAsync("Resource[IO, *]", implicit ec => MonadErrorTests[Resource[IO, *], Throwable].monadError[Int, Int, Int])
  checkAllAsync("Resource[IO, Int]", implicit ec => MonoidTests[Resource[IO, Int]].monoid)
  checkAllAsync("Resource[IO, *]", implicit ec => SemigroupKTests[Resource[IO, *]].semigroupK[Int])
  checkAllAsync(
    "Resource.Par[IO, *]",
    implicit ec => {
      implicit val cs: ContextShift[IO] = ec.ioContextShift
      CommutativeApplicativeTests[Resource.Par[IO, *]].commutativeApplicative[Int, Int, Int]
    }
  )
  checkAllAsync(
    "Resource[IO, *]",
    implicit ec => {
      implicit val cs: ContextShift[IO] = ec.ioContextShift

      // do NOT inline this val; it causes the 2.13.0 compiler to crash for... reasons (see: scala/bug#11732)
      val module = ParallelTests[Resource[IO, *]]
      module.parallel[Int, Int]
    }
  )

  propertyAsync("Resource.make is equivalent to a partially applied bracket") { implicit ec =>
    forAll { (acquire: IO[String], release: String => IO[Unit], f: String => IO[String]) =>
      acquire.bracket(f)(release) <-> Resource.make(acquire)(release).use(f)
    }
  }

  property("releases resources in reverse order of acquisition") {
    forAll { (as: List[(Int, Either[Throwable, Unit])]) =>
      var released: List[Int] = Nil
      val r = as.traverse {
        case (a, e) =>
          Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
      }
      r.use(IO.pure).attempt.unsafeRunSync()
      released <-> as.map(_._1)
    }
  }

  property("releases both resources on combine") {
    forAll { (rx: Resource[IO, Int], ry: Resource[IO, Int]) =>
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(r: Resource[IO, Int]) = r.flatMap { a =>
        Resource.make(IO(acquired += a) *> IO.pure(a))(a => IO(released += a)).as(())
      }
      observe(rx).combine(observe(ry)).use(_ => IO.unit).attempt.unsafeRunSync()
      released <-> acquired
    }
  }

  property("releases both resources on combineK") {
    forAll { (rx: Resource[IO, Int], ry: Resource[IO, Int]) =>
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(r: Resource[IO, Int]) = r.flatMap { a =>
        Resource.make(IO(acquired += a) *> IO.pure(a))(a => IO(released += a)).as(())
      }
      observe(rx).combineK(observe(ry)).use(_ => IO.unit).attempt.unsafeRunSync()
      released <-> acquired
    }
  }

  property("releases both resources on combineK when using a SemigroupK instance that discards allocated values") {
    implicit val sgk: SemigroupK[IO] = new SemigroupK[IO] {
      override def combineK[A](x: IO[A], y: IO[A]): IO[A] = x <* y
    }
    forAll { (rx: Resource[IO, Int], ry: Resource[IO, Int]) =>
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(r: Resource[IO, Int]) = r.flatMap { a =>
        Resource.make(IO(acquired += a) *> IO.pure(a))(a => IO(released += a)).as(())
      }
      observe(rx).combineK(observe(ry)).use(_ => IO.unit).attempt.unsafeRunSync()
      released <-> acquired
    }
  }

  test("resource from AutoCloseable is auto closed") {
    var closed = false
    val autoCloseable = new AutoCloseable {
      override def close(): Unit = closed = true
    }

    val result = Resource
      .fromAutoCloseable(IO(autoCloseable))
      .use(_ => IO.pure("Hello world"))
      .unsafeRunSync()

    assertEquals(result, "Hello world")
    assertEquals(closed, true)
  }

  testAsync("resource from AutoCloseableBlocking is auto closed and executes in the blocking context") { implicit ec =>
    implicit val ctx: ContextShift[IO] = ec.ioContextShift

    val blockingEc = TestContext()
    val blocker = Blocker.liftExecutionContext(blockingEc)

    var closed = false
    val autoCloseable = new AutoCloseable {
      override def close(): Unit = closed = true
    }

    var acquired = false
    val acquire = IO {
      acquired = true
      autoCloseable
    }

    val result = Resource
      .fromAutoCloseableBlocking(blocker)(acquire)
      .use(_ => IO.pure("Hello world"))
      .unsafeToFuture()

    // Check that acquire ran inside the blocking context.
    ec.tick()
    assertEquals(acquired, false)
    blockingEc.tick()
    assertEquals(acquired, true)

    // Check that close was called and ran inside the blocking context.
    ec.tick()
    assertEquals(closed, false)
    blockingEc.tick()
    assertEquals(closed, true)

    // Check the final result.
    ec.tick()
    assertEquals(result.value, Some(Success("Hello world")))
  }

  propertyAsync("eval") { implicit ec =>
    forAll { (fa: IO[String]) =>
      Resource.eval(fa).use(IO.pure) <-> fa
    }
  }

  testAsync("eval - interruption") { implicit ec =>
    implicit val timer: Timer[IO] = ec.ioTimer
    implicit val ctx: ContextShift[IO] = ec.ioContextShift

    def p =
      Deferred[IO, ExitCase[Throwable]]
        .flatMap { stop =>
          val r = Resource
            .eval(IO.never: IO[Int])
            .use(IO.pure)
            .guaranteeCase(stop.complete)

          r.start.flatMap { fiber =>
            timer.sleep(200.millis) >> fiber.cancel >> stop.get
          }
        }
        .timeout(2.seconds)

    val res = p.unsafeToFuture()

    ec.tick(3.seconds)

    assertEquals(res.value, Some(Success(ExitCase.Canceled)))
  }

  propertyAsync("eval(fa) <-> liftK.apply(fa)") { implicit ec =>
    forAll { (fa: IO[String], f: String => IO[Int]) =>
      Resource.eval(fa).use(f) <-> Resource.liftK[IO].apply(fa).use(f)
    }
  }

  propertyAsync("evalMap") { implicit ec =>
    forAll { (f: Int => IO[Int]) =>
      Resource.eval(IO(0)).evalMap(f).use(IO.pure) <-> f(0)
    }
  }

  propertyAsync("(evalMap with error <-> IO.raiseError") { implicit ec =>
    case object Foo extends Exception
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    forAll { (g: Int => IO[Int]) =>
      val effect: Int => IO[Int] = a => (g(a) <* IO(throw Foo))
      Resource.eval(IO(0)).evalMap(effect).use(IO.pure) <-> IO.raiseError(Foo)
    }
  }

  propertyAsync("evalTap") { implicit ec =>
    forAll { (f: Int => IO[Int]) =>
      Resource.eval(IO(0)).evalTap(f).use(IO.pure) <-> f(0).as(0)
    }
  }

  propertyAsync("evalTap with cancellation <-> IO.never") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    forAll { (g: Int => IO[Int]) =>
      val effect: Int => IO[Int] = a =>
        for {
          f <- (g(a) <* IO.cancelBoundary).start
          _ <- f.cancel
          r <- f.join
        } yield r

      Resource.eval(IO(0)).evalTap(effect).use(IO.pure) <-> IO.never
    }
  }

  propertyAsync("(evalTap with error <-> IO.raiseError") { implicit ec =>
    case object Foo extends Exception
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    forAll { (g: Int => IO[Int]) =>
      val effect: Int => IO[Int] = a => (g(a) <* IO(throw Foo))
      Resource.eval(IO(0)).evalTap(effect).use(IO.pure) <-> IO.raiseError(Foo)
    }
  }

  propertyAsync("mapK") { implicit ec =>
    forAll { (fa: Kleisli[IO, Int, Int]) =>
      val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
        override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
      }
      Resource.eval(fa).mapK(runWithTwo).use(IO.pure) <-> fa(2)
    }
  }

  test("mapK should preserve ExitCode-specific behaviour") {
    val takeAnInteger = new ~>[IO, Kleisli[IO, Int, *]] {
      override def apply[A](fa: IO[A]): Kleisli[IO, Int, A] = Kleisli.liftF(fa)
    }

    def sideEffectyResource: (AtomicBoolean, Resource[IO, Unit]) = {
      val cleanExit = new java.util.concurrent.atomic.AtomicBoolean(false)
      val res = Resource.makeCase(IO.unit) {
        case (_, ExitCase.Completed) =>
          IO {
            cleanExit.set(true)
          }
        case _ => IO.unit
      }
      (cleanExit, res)
    }

    val (clean, res) = sideEffectyResource
    res.use(_ => IO.unit).attempt.unsafeRunSync()
    assertEquals(clean.get(), true)

    val (clean1, res1) = sideEffectyResource
    res1.use(_ => IO.raiseError(new Throwable("oh no"))).attempt.unsafeRunSync()
    assertEquals(clean1.get(), false)

    val (clean2, res2) = sideEffectyResource
    res2
      .mapK(takeAnInteger)
      .use(_ => Kleisli.liftF(IO.raiseError[Unit](new Throwable("oh no"))))
      .run(0)
      .attempt
      .unsafeRunSync()
    assertEquals(clean2.get(), false)
  }

  propertyAsync("allocated produces the same value as the resource") { implicit ec =>
    forAll { (resource: Resource[IO, Int]) =>
      val a0 = Resource(resource.allocated).use(IO.pure).attempt
      val a1 = resource.use(IO.pure).attempt

      a0 <-> a1
    }
  }

  test("allocate does not release until close is invoked") {
    val released = new java.util.concurrent.atomic.AtomicBoolean(false)
    val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
    val resource = Resource.eval(IO.unit)

    // Dotty fails to infer functor syntax if this line is in the for comprehension
    val allocated = (release *> resource).allocated

    val prog = for {
      res <- allocated
      (_, close) = res
      _ <- IO(assertEquals(released.get(), false))
      _ <- close
      _ <- IO(assertEquals(released.get(), true))
    } yield ()

    prog.unsafeRunSync()
  }

  test("allocate does not release until close is invoked on mapK'd Resources") {
    val released = new java.util.concurrent.atomic.AtomicBoolean(false)

    val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
      override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
    }
    val takeAnInteger = new ~>[IO, Kleisli[IO, Int, *]] {
      override def apply[A](fa: IO[A]): Kleisli[IO, Int, A] = Kleisli.liftF(fa)
    }
    val plusOne = Kleisli { (i: Int) =>
      IO(i + 1)
    }
    val plusOneResource = Resource.eval(plusOne)

    val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
    val resource = Resource.eval(IO.unit)

    // Dotty fails to infer functor syntax if this line is in the for comprehension
    val allocated = ((release *> resource).mapK(takeAnInteger) *> plusOneResource).mapK(runWithTwo).allocated

    val prog = for {
      res <- allocated
      (_, close) = res
      _ <- IO(assertEquals(released.get(), false))
      _ <- close
      _ <- IO(assertEquals(released.get(), true))
    } yield ()

    prog.unsafeRunSync()
  }

  test("safe attempt suspended resource") {
    val exception = new Exception("boom!")
    val suspend = Resource.suspend[IO, Int](IO.raiseError(exception))
    assertEquals(suspend.attempt.use(IO.pure).unsafeRunSync(), Left(exception))
  }

  property("combineK - should behave like orElse when underlying effect does") {
    forAll { (r1: Resource[IO, Int], r2: Resource[IO, Int]) =>
      val lhs = r1.orElse(r2).use(IO.pure).attempt.unsafeRunSync()
      val rhs = (r1 <+> r2).use(IO.pure).attempt.unsafeRunSync()

      lhs <-> rhs
    }
  }

  property("combineK - should behave like underlying effect") {
    import cats.data.OptionT
    forAll { (ot1: OptionT[IO, Int], ot2: OptionT[IO, Int]) =>
      val lhs: Either[Throwable, Option[Int]] =
        Resource.eval[OptionT[IO, *], Int](ot1 <+> ot2).use(OptionT.pure[IO](_)).value.attempt.unsafeRunSync()
      val rhs: Either[Throwable, Option[Int]] =
        (Resource.eval[OptionT[IO, *], Int](ot1) <+> Resource.eval[OptionT[IO, *], Int](ot2))
          .use(OptionT.pure[IO](_))
          .value
          .attempt
          .unsafeRunSync()

      lhs <-> rhs
    }
  }

  propertyAsync("parZip - releases resources in reverse order of acquisition") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    // conceptually asserts that:
    //   forAll (r: Resource[F, A]) then r <-> r.parZip(Resource.unit) <-> Resource.unit.parZip(r)
    // needs to be tested manually to assert the equivalence during cleanup as well
    forAll { (as: List[(Int, Either[Throwable, Unit])], rhs: Boolean) =>
      var released: List[Int] = Nil
      val r = as.traverse {
        case (a, e) =>
          Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
      }
      val unit = ().pure[Resource[IO, *]]
      val p = if (rhs) r.parZip(unit) else unit.parZip(r)

      p.use(IO.pure).attempt.unsafeToFuture()
      ec.tick()
      released <-> as.map(_._1)
    }
  }

  testAsync("parZip - parallel acquisition and release") { implicit ec =>
    implicit val timer: Timer[IO] = ec.ioTimer
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    var leftAllocated = false
    var rightAllocated = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    val wait = IO.sleep(1.second)
    val lhs = Resource.make(wait >> IO { leftAllocated = true }) { _ =>
      IO { leftReleasing = true } >> wait >> IO { leftReleased = true }
    }
    val rhs = Resource.make(wait >> IO { rightAllocated = true }) { _ =>
      IO { rightReleasing = true } >> wait >> IO { rightReleased = true }
    }

    (lhs, rhs).parTupled.use(_ => wait).unsafeToFuture()

    // after 1 second:
    //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
    //  resources are still open during `use` (correctness)
    ec.tick(1.second)
    assertEquals(leftAllocated, true)
    assertEquals(rightAllocated, true)
    assertEquals(leftReleasing, false)
    assertEquals(rightReleasing, false)

    // after 2 seconds:
    //  both resources have started cleanup (correctness)
    ec.tick(1.second)
    assertEquals(leftReleasing, true)
    assertEquals(rightReleasing, true)
    assertEquals(leftReleased, false)
    assertEquals(rightReleased, false)

    // after 3 seconds:
    //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
    ec.tick(1.second)
    assertEquals(leftReleased, true)
    assertEquals(rightReleased, true)
  }

  testAsync("parZip - safety: lhs error during rhs interruptible region") { implicit ec =>
    implicit val timer: Timer[IO] = ec.ioTimer
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    var leftAllocated = false
    var rightAllocated = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    def wait(n: Int) = IO.sleep(n.seconds)
    val lhs = for {
      _ <- Resource.make(wait(1) >> IO { leftAllocated = true }) { _ =>
        IO { leftReleasing = true } >> wait(1) >> IO { leftReleased = true }
      }
      _ <- Resource.eval(wait(1) >> IO.raiseError[Unit](new Exception))
    } yield ()

    val rhs = for {
      _ <- Resource.make(wait(1) >> IO { rightAllocated = true }) { _ =>
        IO { rightReleasing = true } >> wait(1) >> IO { rightReleased = true }
      }
      _ <- Resource.eval(wait(2))
    } yield ()

    (lhs, rhs).parTupled
      .use(_ => IO.unit)
      .handleError(_ => ())
      .unsafeToFuture()

    // after 1 second:
    //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
    //  resources are still open during `flatMap` (correctness)
    ec.tick(1.second)
    assertEquals(leftAllocated, true)
    assertEquals(rightAllocated, true)
    assertEquals(leftReleasing, false)
    assertEquals(rightReleasing, false)

    // after 2 seconds:
    //  both resources have started cleanup (interruption, or rhs would start releasing after 3 seconds)
    ec.tick(1.second)
    assertEquals(leftReleasing, true)
    assertEquals(rightReleasing, true)
    assertEquals(leftReleased, false)
    assertEquals(rightReleased, false)

    // after 3 seconds:
    //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
    ec.tick(1.second)
    assertEquals(leftReleased, true)
    assertEquals(rightReleased, true)
  }

  testAsync("parZip - safety: rhs error during lhs uninterruptible region") { implicit ec =>
    implicit val timer: Timer[IO] = ec.ioTimer
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    var leftAllocated = false
    var rightAllocated = false
    var rightErrored = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    def wait(n: Int) = IO.sleep(n.seconds)
    val lhs = Resource.make(wait(3) >> IO { leftAllocated = true }) { _ =>
      IO { leftReleasing = true } >> wait(1) >> IO { leftReleased = true }
    }
    val rhs = for {
      _ <- Resource.make(wait(1) >> IO { rightAllocated = true }) { _ =>
        IO { rightReleasing = true } >> wait(1) >> IO { rightReleased = true }
      }
      _ <- Resource.make(wait(1) >> IO { rightErrored = true } >> IO.raiseError[Unit](new Exception))(_ => IO.unit)
    } yield ()

    (lhs, rhs).parTupled
      .use(_ => wait(1))
      .handleError(_ => ())
      .unsafeToFuture()

    // after 1 second:
    //  rhs has partially allocated, lhs executing
    ec.tick(1.second)
    assertEquals(leftAllocated, false)
    assertEquals(rightAllocated, true)
    assertEquals(rightErrored, false)
    assertEquals(leftReleasing, false)
    assertEquals(rightReleasing, false)

    // after 2 seconds:
    //  rhs has failed, release blocked since lhs is in uninterruptible allocation
    ec.tick(1.second)
    assertEquals(leftAllocated, false)
    assertEquals(rightAllocated, true)
    assertEquals(rightErrored, true)
    assertEquals(leftReleasing, false)
    assertEquals(rightReleasing, false)

    // after 3 seconds:
    //  lhs completes allocation (concurrency, serially it would happen after 4 seconds)
    //  both resources have started cleanup (correctness, error propagates to both sides)
    ec.tick(1.second)
    assertEquals(leftAllocated, true)
    assertEquals(leftReleasing, true)
    assertEquals(rightReleasing, true)
    assertEquals(leftReleased, false)
    assertEquals(rightReleased, false)

    // after 4 seconds:
    //  both resource have terminated cleanup (concurrency, serially it would happen after 5 seconds)
    ec.tick(1.second)
    assertEquals(leftReleased, true)
    assertEquals(rightReleased, true)
  }

  testAsync("onFinalizeCase - interruption") { implicit ec =>
    implicit val timer: Timer[IO] = ec.ioTimer
    implicit val ctx: ContextShift[IO] = ec.ioContextShift

    def p =
      Deferred[IO, ExitCase[Throwable]]
        .flatMap { stop =>
          val r = Resource
            .eval(IO.never: IO[Int])
            .onFinalizeCase(stop.complete)
            .use(IO.pure)

          r.start.flatMap { fiber =>
            timer.sleep(200.millis) >> fiber.cancel >> stop.get
          }
        }
        .timeout(2.seconds)

    val res = p.unsafeToFuture()

    ec.tick(3.seconds)

    assertEquals(res.value, Some(Success(ExitCase.Canceled)))
  }

  property("onFinalize - runs after existing finalizer") {
    forAll { (rx: Resource[IO, Int], y: Int) =>
      var acquired: List[Int] = Nil
      var released: List[Int] = Nil

      def observe(r: Resource[IO, Int]) =
        r.flatMap { a =>
          Resource
            .make(IO {
              acquired = a :: acquired
            } *> IO.pure(a))(a =>
              IO {
                released = a :: released
              }
            )
            .as(())
        }

      observe(rx)
        .onFinalize(IO {
          released = y :: released
        })
        .use(_ => IO.unit)
        .attempt
        .unsafeRunSync()
      released <-> y :: acquired
    }
  }
}
