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
package internals

import cats.effect.IO

class ForwardCancelableTests extends CatsEffectSuite {
  test("cancel() after complete") {
    var effect = 0

    val ref = ForwardCancelable()
    ref.complete(IO(effect += 1))
    assertEquals(effect, 0)

    ref.cancel.unsafeRunAsyncAndForget()
    assertEquals(effect, 1)

    // Weak idempotency guarantees (not thread-safe)
    ref.cancel.unsafeRunAsyncAndForget()
    assertEquals(effect, 1)
  }

  test("cancel() before complete") {
    var effect = 0

    val ref = ForwardCancelable()
    ref.cancel.unsafeRunAsyncAndForget()
    assertEquals(effect, 0)

    ref.complete(IO(effect += 1))
    assertEquals(effect, 1)

    intercept[IllegalStateException](ref.complete(IO(effect += 2)))
    // completed task was canceled before error was thrown
    assertEquals(effect, 3)

    ref.cancel.unsafeRunAsyncAndForget()
    assertEquals(effect, 3)
  }

  test("complete twice before cancel") {
    var effect = 0

    val ref = ForwardCancelable()
    ref.complete(IO(effect += 1))
    assertEquals(effect, 0)

    intercept[IllegalStateException](ref.complete(IO(effect += 2)))
    assertEquals(effect, 2)

    ref.cancel.unsafeRunAsyncAndForget()
    assertEquals(effect, 3)
  }
}
