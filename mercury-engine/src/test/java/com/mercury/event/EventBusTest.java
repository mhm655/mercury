package com.mercury.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Both buses are held to the same delivery contract, and then each to the guarantees only it
 * makes - which is the point of having one interface: a test that passes on the synchronous
 * bus must describe behaviour the asynchronous one really has too, or the default is a
 * dishonest stand-in for it.
 */
class EventBusTest {

    private record Priced(String symbol) {
    }

    private record Traded(String symbol) {
    }

    /** Delivery rules neither implementation is allowed to differ on. */
    @Nested
    @DisplayName("both implementations")
    class SharedContract {

        private List<Supplier<EventBus>> buses() {
            return List.of(SynchronousEventBus::new, AsynchronousEventBus::new);
        }

        /** Publishes, then makes sure delivery has finished, whichever bus this is. */
        private void publishAndSettle(EventBus bus, Object... events) {
            for (Object event : events) {
                bus.publish(event);
            }
            if (bus instanceof AsynchronousEventBus async) {
                async.close();
            }
        }

        @Test
        @DisplayName("an event reaches every subscriber of its type, in subscription order")
        void deliversToSubscribersInOrder() {
            for (Supplier<EventBus> factory : buses()) {
                EventBus bus = factory.get();
                List<String> calls = new CopyOnWriteArrayList<>();
                bus.subscribe(Priced.class, event -> calls.add("first:" + event.symbol()));
                bus.subscribe(Priced.class, event -> calls.add("second:" + event.symbol()));

                publishAndSettle(bus, new Priced("AAPL"));

                assertThat(calls).as("%s", bus).containsExactly("first:AAPL", "second:AAPL");
            }
        }

        @Test
        @DisplayName("a subscriber hears only its own event type")
        void doesNotDeliverOtherTypes() {
            for (Supplier<EventBus> factory : buses()) {
                EventBus bus = factory.get();
                List<Object> priced = new CopyOnWriteArrayList<>();
                bus.subscribe(Priced.class, priced::add);

                publishAndSettle(bus, new Traded("AAPL"));

                assertThat(priced).as("%s", bus).isEmpty();
            }
        }

        @Test
        @DisplayName("a subscriber to a supertype hears subtypes too")
        void deliversBySupertype() {
            for (Supplier<EventBus> factory : buses()) {
                EventBus bus = factory.get();
                List<Object> everything = new CopyOnWriteArrayList<>();
                bus.subscribe(Object.class, everything::add);

                publishAndSettle(bus, new Priced("AAPL"), new Traded("MSFT"));

                assertThat(everything).as("%s", bus).hasSize(2);
            }
        }

        @Test
        @DisplayName("publishing with nobody listening is not an error")
        void publishingIntoTheVoidIsFine() {
            for (Supplier<EventBus> factory : buses()) {
                EventBus bus = factory.get();

                publishAndSettle(bus, new Priced("AAPL"));
            }
        }

        @Test
        @DisplayName("events arrive in publication order")
        void preservesPublicationOrder() {
            for (Supplier<EventBus> factory : buses()) {
                EventBus bus = factory.get();
                List<String> seen = new CopyOnWriteArrayList<>();
                bus.subscribe(Priced.class, event -> seen.add(event.symbol()));

                publishAndSettle(bus, new Priced("1"), new Priced("2"), new Priced("3"));

                assertThat(seen).as("%s", bus).containsExactly("1", "2", "3");
            }
        }
    }

    @Nested
    @DisplayName("synchronous")
    class Synchronous {

        @Test
        @DisplayName("delivery happens on the publishing thread, before publish returns")
        void deliversInline() {
            EventBus bus = new SynchronousEventBus();
            Thread publisher = Thread.currentThread();
            List<Thread> deliveredOn = new ArrayList<>();
            bus.subscribe(Priced.class, event -> deliveredOn.add(Thread.currentThread()));

            bus.publish(new Priced("AAPL"));

            assertThat(deliveredOn).containsExactly(publisher);
        }

        @Test
        @DisplayName("a subscriber that throws fails the publisher")
        void subscriberFailureReachesThePublisher() {
            // The deliberate difference from the async bus: if booking a trade fails, the
            // caller that executed it finds out, rather than the execution being reported as
            // successful while the position never arrived.
            EventBus bus = new SynchronousEventBus();
            bus.subscribe(Priced.class, event -> {
                throw new IllegalStateException("ledger rejected it");
            });

            assertThatThrownBy(() -> bus.publish(new Priced("AAPL")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ledger rejected it");
        }
    }

    @Nested
    @DisplayName("asynchronous")
    class Asynchronous {

        @Test
        @DisplayName("publish returns without waiting for a slow subscriber")
        void publishDoesNotWait() throws InterruptedException {
            CountDownLatch subscriberStarted = new CountDownLatch(1);
            CountDownLatch releaseSubscriber = new CountDownLatch(1);
            try (AsynchronousEventBus bus = new AsynchronousEventBus()) {
                bus.subscribe(Priced.class, event -> {
                    subscriberStarted.countDown();
                    awaitLatch(releaseSubscriber);
                });

                bus.publish(new Priced("AAPL"));
                // Returned while the subscriber is still inside its callback: on the
                // synchronous bus this line would not be reached until it finished.
                assertThat(subscriberStarted.await(10, TimeUnit.SECONDS)).isTrue();

                releaseSubscriber.countDown();
            }
        }

        @Test
        @DisplayName("delivery happens on the dispatcher thread, not the publisher's")
        void deliversOffThread() {
            List<Thread> deliveredOn = new CopyOnWriteArrayList<>();
            try (AsynchronousEventBus bus = new AsynchronousEventBus()) {
                bus.subscribe(Priced.class, event -> deliveredOn.add(Thread.currentThread()));
                bus.publish(new Priced("AAPL"));
            }

            assertThat(deliveredOn).hasSize(1);
            assertThat(deliveredOn.get(0)).isNotEqualTo(Thread.currentThread());
        }

        @Test
        @DisplayName("a subscriber that throws is isolated: later events still arrive")
        void subscriberFailureIsIsolated() {
            List<Object> delivered = new CopyOnWriteArrayList<>();
            List<RuntimeException> failures = new CopyOnWriteArrayList<>();
            try (AsynchronousEventBus bus = new AsynchronousEventBus(
                    (event, failure) -> failures.add(failure))) {
                bus.subscribe(Priced.class, event -> {
                    throw new IllegalStateException("subscriber is broken");
                });
                bus.subscribe(Priced.class, delivered::add);

                bus.publish(new Priced("first"));
                bus.publish(new Priced("second"));
                bus.close();

                assertThat(delivered).hasSize(2);
                assertThat(failures).hasSize(2);
                assertThat(bus.failureCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a failure handler that itself throws does not kill the dispatcher")
        void aBrokenFailureHandlerDoesNotStopDelivery() {
            List<Object> delivered = new CopyOnWriteArrayList<>();
            try (AsynchronousEventBus bus = new AsynchronousEventBus((event, failure) -> {
                throw new IllegalStateException("the handler is broken too");
            })) {
                bus.subscribe(Priced.class, event -> {
                    throw new IllegalStateException("subscriber is broken");
                });
                bus.subscribe(Traded.class, delivered::add);

                bus.publish(new Priced("boom"));
                bus.publish(new Traded("still delivered"));
                bus.close();

                assertThat(delivered).hasSize(1);
            }
        }

        @Test
        @DisplayName("a subscriber that throws an Error closes the bus rather than leaving it silently deaf")
        void subscriberErrorClosesTheBus() throws InterruptedException {
            CountDownLatch threwFatally = new CountDownLatch(1);
            AsynchronousEventBus bus = new AsynchronousEventBus();
            bus.subscribe(Priced.class, event -> {
                threwFatally.countDown();
                throw new OutOfMemoryError("simulated");
            });

            bus.publish(new Priced("AAPL"));

            assertThat(threwFatally.await(10, TimeUnit.SECONDS)).isTrue();
            // The dispatcher thread is dying asynchronously from the Error above; give it a
            // moment to mark the bus closed before asserting publish() now rejects rather than
            // silently queuing onto a dispatcher that no longer exists.
            awaitClosed(bus);

            assertThatThrownBy(() -> bus.publish(new Traded("MSFT")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        private void awaitClosed(AsynchronousEventBus bus) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try {
                    bus.publish(new Priced("polling-for-close"));
                } catch (IllegalStateException e) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError("bus did not close after its dispatcher thread died");
        }

        @Test
        @DisplayName("close delivers what was already queued")
        void closeDrains() {
            List<Object> delivered = new CopyOnWriteArrayList<>();
            AsynchronousEventBus bus = new AsynchronousEventBus();
            bus.subscribe(Priced.class, delivered::add);
            for (int i = 0; i < 500; i++) {
                bus.publish(new Priced("event-" + i));
            }

            bus.close();

            assertThat(delivered).hasSize(500);
        }

        @Test
        @DisplayName("publishing after close is refused rather than silently dropped")
        void publishAfterCloseThrows() {
            AsynchronousEventBus bus = new AsynchronousEventBus();
            bus.close();
            bus.close();

            assertThatThrownBy(() -> bus.publish(new Priced("AAPL")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("events published from several threads all arrive")
        void concurrentPublishersAllArrive() throws InterruptedException {
            int publishers = 8;
            int perPublisher = 250;
            List<Object> delivered = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(publishers);

            try (AsynchronousEventBus bus = new AsynchronousEventBus()) {
                bus.subscribe(Priced.class, delivered::add);
                for (int p = 0; p < publishers; p++) {
                    new Thread(() -> {
                        for (int i = 0; i < perPublisher; i++) {
                            bus.publish(new Priced("x"));
                        }
                        done.countDown();
                    }).start();
                }
                assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
                bus.close();

                assertThat(delivered).hasSize(publishers * perPublisher);
            }
        }
    }

    @Nested
    @DisplayName("ignoring")
    class Ignoring {

        @Test
        @DisplayName("accepts subscribers and delivers nothing to them")
        void deliversNothing() {
            List<Object> delivered = new ArrayList<>();
            EventBus bus = EventBus.ignoring();
            bus.subscribe(Priced.class, delivered::add);

            bus.publish(new Priced("AAPL"));

            assertThat(delivered).isEmpty();
        }

        @Test
        @DisplayName("still rejects a null event or subscriber")
        void rejectsNulls() {
            EventBus bus = EventBus.ignoring();

            assertThatThrownBy(() -> bus.publish(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> bus.subscribe(Priced.class, (Consumer<Priced>) null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the test to release it");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
