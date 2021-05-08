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

package cats.effect.concurrent

import cats.Parallel
import cats.effect._
import cats.effect.implicits._
import cats.implicits._

/**
 * A fiber-based supervisor that monitors the lifecycle of all fibers
 * that are started via its interface. The supervisor is managed by a singular
 * fiber to which the lifecycles of all spawned fibers are bound.
 *
 * Whereas [[Concurrent.background]] links the lifecycle of the spawned fiber to
 * the calling fiber, starting a fiber via a [[Supervisor]] links the lifecycle
 * of the spawned fiber to the supervisor fiber. This is useful when the scope
 * of some fiber must survive the spawner, but should still be confined within
 * some "larger" scope.
 *
 * The fibers started via the supervisor are guaranteed to be terminated when
 * the supervisor fiber is terminated. When a supervisor fiber is canceled, all
 * active and queued fibers will be safely finalized before finalization of
 * the supervisor is complete.
 *
 * The following diagrams illustrate the lifecycle of a fiber spawned via
 * [[Concurrent.start]], [[Concurrent.background]], and [[Supervisor]]. In each
 * example, some fiber A is spawning another fiber B. Each box represents the
 * lifecycle of a fiber. If a box is enclosed within another box, it means that
 * the lifecycle of the former is confined within the lifecycle of the latter.
 * In other words, if an outer fiber terminates, the inner fibers are
 * guaranteed to be terminated as well.
 *
 * start:
 * {{{
 * Fiber A lifecycle
 * +---------------------+
 * |                 |   |
 * +-----------------|---+
 *                   |
 *                   |A starts B
 * Fiber B lifecycle |
 * +-----------------|---+
 * |                 +   |
 * +---------------------+
 * }}}
 *
 * background:
 * {{{
 * Fiber A lifecycle
 * +------------------------+
 * |                    |   |
 * | Fiber B lifecycle  |A starts B
 * | +------------------|-+ |
 * | |                  | | |
 * | +--------------------+ |
 * +------------------------+
 * }}}
 *
 * Supervisor:
 * {{{
 * Supervisor lifecycle
 * +---------------------+
 * | Fiber B lifecycle   |
 * | +-----------------+ |
 * | |               + | |
 * | +---------------|-+ |
 * +-----------------|---+
 *                   |
 *                   | A starts B
 * Fiber A lifecycle |
 * +-----------------|---+
 * |                 |   |
 * +---------------------+
 * }}}
 *
 * [[Supervisor]] should be used when fire-and-forget semantics are desired.
 */
trait Supervisor[F[_]] {

  /**
   * Starts the supplied effect `fa` on the supervisor.
   *
   * @return a [[Fiber]] that represents a handle to the started fiber.
   */
  def supervise[A](fa: F[A]): F[Fiber[F, A]]
}

object Supervisor {

  private class Token

  /**
   * Creates a [[Resource]] scope within which fibers can be monitored. When
   * this scope exits, all supervised fibers will be finalized.
   */
  def apply[F[_]](
    implicit F: Concurrent[F],
    P: Parallel[F]
  ): Resource[F, Supervisor[F]] =
    for {
      stateRef <- Resource.make(Ref.of[F, Map[Token, F[Unit]]](Map())) { state =>
        state.get.flatMap { fibers =>
          fibers.values.toList.parSequence
        }.void
      }
    } yield {
      new Supervisor[F] {
        override def supervise[A](fa: F[A]): F[Fiber[F, A]] =
          F.uncancelable {
            Deferred[F, Unit].flatMap { gate =>
              val token = new Token
              val action = fa.guarantee(gate.get *> stateRef.update(_ - token))
              F.start(action).flatMap { fiber =>
                stateRef.update(_ + (token -> fiber.cancel)).as(fiber) <* gate
                  .complete(())
              }
            }
          }
      }
    }
}
