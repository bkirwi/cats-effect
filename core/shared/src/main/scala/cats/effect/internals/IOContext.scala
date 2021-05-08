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

package cats.effect.internals

import cats.effect.tracing.{IOEvent, IOTrace}
import cats.effect.internals.TracingPlatform.traceBufferLogSize

/**
 * INTERNAL API — Holds state related to the execution of
 * an IO and should be threaded across multiple invocations
 * of the run-loop associated with the same fiber.
 */
final private[effect] class IOContext() {

  private[this] val events: RingBuffer[IOEvent] = new RingBuffer(traceBufferLogSize)
  private[this] var captured: Int = 0
  private[this] var omitted: Int = 0

  def pushEvent(fr: IOEvent): Unit = {
    captured += 1
    if (events.push(fr) != null) omitted += 1
  }

  def trace(): IOTrace =
    IOTrace(events.toList, captured, omitted)

  def getStackTraces(): List[IOEvent.StackTrace] =
    events.toList.collect { case ev: IOEvent.StackTrace => ev }

}
