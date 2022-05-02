package zio.interop.reactivestreams

import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.reactivestreams.tck.TestEnvironment
import org.reactivestreams.tck.TestEnvironment.ManualPublisher
import zio.Chunk
import zio.Exit
import zio.Fiber
import zio.Promise
import zio.Supervisor
import zio.Task
import zio.UIO
import zio.ZEnvironment
import zio.ZIO
import zio.ZTraceElement
import zio.durationInt
import zio.stream.Sink
import zio.stream.Stream
import zio.test.Assertion._
import zio.test._

object PublisherToStreamSpec extends ZIOSpecDefault {

  override def spec =
    suite("Converting a `Publisher` to a `Stream`")(
      test("works with a well behaved `Publisher`") {
        assertM(publish(seq, None))(succeeds(equalTo(seq)))
      },
      test("fails with an initially failed `Publisher`") {
        assertM(publish(Chunk.empty, Some(e)))(fails(equalTo(e)))
      },
      test("fails with an eventually failing `Publisher`") {
        assertM(publish(seq, Some(e)))(fails(equalTo(e)))
      },
      test("does not fail a fiber on failing `Publisher`") {

        val publisher = new Publisher[Int] {
          override def subscribe(s: Subscriber[_ >: Int]): Unit =
            s.onSubscribe(
              new Subscription {
                override def request(n: Long): Unit = s.onError(new Throwable("boom!"))
                override def cancel(): Unit         = ()
              }
            )
        }

        val supervisor =
          new Supervisor[Boolean] {

            @transient var failedAFiber = false

            def value(implicit trace: ZTraceElement): UIO[Boolean] =
              ZIO.succeed(failedAFiber)

            def unsafeOnStart[R, E, A](
              environment: ZEnvironment[R],
              effect: ZIO[R, E, A],
              parent: Option[Fiber.Runtime[Any, Any]],
              fiber: Fiber.Runtime[E, A]
            ): Unit = ()

            def unsafeOnEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A]): Unit =
              if (value.isFailure) failedAFiber = true

          }

        for {
          outerRuntime <- ZIO.runtime[Any]
          runtime       = outerRuntime.mapRuntimeConfig(_.copy(supervisor = supervisor))
          exit         <- runtime.run(publisher.toZIOStream().runDrain.exit)
          failed       <- supervisor.value
        } yield assert(exit)(fails(anything)) && assert(failed)(isFalse)

      },
      test("does not freeze on stream end") {
        withProbe(probe =>
          for {
            fiber <- Stream
                       .fromZIO(
                         ZIO.succeed(
                           probe.toZIOStream()
                         )
                       )
                       .flatMap(identity)
                       .run(Sink.collectAll[Int])
                       .fork
            _ <- ZIO.attemptBlockingInterrupt(probe.expectRequest())
            _ <- ZIO.succeed(probe.sendNext(1))
            _ <- ZIO.succeed(probe.sendCompletion)
            r <- fiber.join
          } yield assert(r)(equalTo(Chunk(1)))
        )
      } @@ TestAspect.timeout(3.seconds),
      test("cancels subscription when interrupted before subscription") {
        val tst =
          for {
            subscriberP    <- Promise.make[Nothing, Subscriber[_]]
            cancelledLatch <- Promise.make[Nothing, Unit]
            subscription = new Subscription {
                             override def request(n: Long): Unit = ()
                             override def cancel(): Unit         = cancelledLatch.unsafeDone(ZIO.unit)
                           }
            probe = new Publisher[Int] {
                      override def subscribe(subscriber: Subscriber[_ >: Int]): Unit =
                        subscriberP.unsafeDone(ZIO.succeedNow(subscriber))
                    }
            fiber      <- probe.toZIOStream(bufferSize).runDrain.fork
            subscriber <- subscriberP.await
            _          <- fiber.interrupt
            _          <- ZIO.succeed(subscriber.onSubscribe(subscription))
            _          <- cancelledLatch.await
          } yield ()
        assertM(tst.exit)(succeeds(anything))
      } @@ TestAspect.nonFlaky @@ TestAspect.timeout(60.seconds),
      test("cancels subscription when interrupted after subscription") {
        withProbe(probe =>
          assertM((for {
            fiber <- probe.toZIOStream(bufferSize).runDrain.fork
            _     <- ZIO.attemptBlockingInterrupt(probe.expectRequest())
            _     <- fiber.interrupt
            _     <- ZIO.attemptBlockingInterrupt(probe.expectCancelling())
          } yield ()).exit)(
            succeeds(isUnit)
          )
        )
      } @@ TestAspect.nonFlaky @@ TestAspect.timeout(60.seconds),
      test("cancels subscription when interrupted during consumption") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toZIOStream(bufferSize).runDrain.fork
            demand <- ZIO.attemptBlockingInterrupt(probe.expectRequest())
            _      <- ZIO.attempt((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- fiber.interrupt
            _      <- ZIO.attemptBlockingInterrupt(probe.expectCancelling())
          } yield ()).exit)(
            succeeds(isUnit)
          )
        )
      } @@ TestAspect.nonFlaky @@ TestAspect.timeout(60.seconds),
      test("cancels subscription on stream end") {
        withProbe(probe =>
          assertM((for {
            fiber  <- probe.toZIOStream(bufferSize).take(1).runDrain.fork
            demand <- ZIO.attemptBlockingInterrupt(probe.expectRequest())
            _      <- ZIO.attempt((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- ZIO.attemptBlockingInterrupt(probe.expectCancelling())
            _      <- fiber.join
          } yield ()).exit)(
            succeeds(isUnit)
          )
        )
      },
      test("cancels subscription on stream error") {
        withProbe(probe =>
          assertM(for {
            fiber  <- probe.toZIOStream(bufferSize).mapZIO(_ => ZIO.fail(new Throwable("boom!"))).runDrain.fork
            demand <- ZIO.attemptBlockingInterrupt(probe.expectRequest())
            _      <- ZIO.attempt((1 to demand.toInt).foreach(i => probe.sendNext(i)))
            _      <- ZIO.attemptBlockingInterrupt(probe.expectCancelling())
            exit   <- fiber.join.exit
          } yield exit)(fails(anything))
        )
      }
    )

  val e: Throwable    = new RuntimeException("boom")
  val seq: Chunk[Int] = Chunk.fromIterable(List.range(0, 100))
  val bufferSize: Int = 10

  def withProbe[R, E0, E >: Throwable, A](f: ManualPublisher[Int] => ZIO[R, E, A]): ZIO[R, E, A] = {
    val testEnv = new TestEnvironment(3000, 500)
    val probe   = new ManualPublisher[Int](testEnv)
    f(probe) <* ZIO.attempt(testEnv.verifyNoAsyncErrorsNoDelay())
  }

  def publish(seq: Chunk[Int], failure: Option[Throwable]): UIO[Exit[Throwable, Chunk[Int]]] = {

    def loop(probe: ManualPublisher[Int], remaining: Chunk[Int]): Task[Unit] =
      for {
        n            <- ZIO.attemptBlockingInterrupt(probe.expectRequest())
        _            <- ZIO.attempt(assert(n.toInt)(isLessThanEqualTo(bufferSize)))
        split         = n.toInt
        (nextN, tail) = remaining.splitAt(split)
        _            <- ZIO.attempt(nextN.foreach(probe.sendNext))
        _ <- if (nextN.size < split)
               ZIO.attempt(failure.fold(probe.sendCompletion())(probe.sendError))
             else loop(probe, tail)
      } yield ()

    val faillable =
      withProbe(probe =>
        for {
          fiber <- probe.toZIOStream(bufferSize).run(Sink.collectAll[Int]).fork
          _     <- loop(probe, seq)
          r     <- fiber.join
        } yield r
      )

    faillable.exit
  }

}
