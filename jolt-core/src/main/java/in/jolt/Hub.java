package in.jolt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Publish/subscribe by topic for SSE streams, WebSocket sessions or any {@link Sink}. Publishing
 * never blocks: each subscriber has a bounded queue drained on its own thread, and one that falls
 * behind is dropped with a warning. Each message gets an id, increasing within a topic.
 */
public final class Hub<T> {

    private static final System.Logger LOG = System.getLogger("in.jolt");
    private static final long HEARTBEAT_MILLIS = 15_000;
    private static final Object CLOSED = new Object();

    /** Receives one message; a throw ends the subscription. */
    @FunctionalInterface
    public interface Sink<T> {
        void send(long id, T message) throws Exception;
    }

    private record Entry<T>(long id, T message) {}

    @FunctionalInterface
    private interface Idle {
        void beat() throws IOException;
    }

    private final class Topic {
        final String name;
        final Set<Subscription> subs = new LinkedHashSet<>();
        final ArrayDeque<Entry<T>> recent = new ArrayDeque<>();
        boolean removed;

        Topic(String name) {
            this.name = name;
        }
    }

    /** One subscriber; closing it unsubscribes. */
    public final class Subscription implements AutoCloseable {
        private final Topic topic;
        private final Sink<T> sink;
        private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(queueSize + 1);
        private boolean open = true;

        private Subscription(Topic topic, Sink<T> sink) {
            this.topic = topic;
            this.sink = sink;
        }

        public synchronized boolean isOpen() {
            return open;
        }

        /** Unsubscribes and drops undelivered messages. Calling it again has no effect. */
        @Override
        public void close() {
            if (shutdown()) {
                detach(topic, this);
            }
        }

        private synchronized boolean offer(Entry<T> e) {
            if (!open) {
                return false;
            }
            if (queue.size() >= queueSize) {
                return false;
            }
            queue.add(e);
            return true;
        }

        private synchronized boolean shutdown() {
            if (!open) {
                return false;
            }
            open = false;
            queue.clear();
            queue.add(CLOSED);
            subscribers.decrementAndGet();
            return true;
        }

        @SuppressWarnings("unchecked")
        private void run(Idle idle) {
            try {
                while (true) {
                    Object o =
                            idle == null
                                    ? queue.take()
                                    : queue.poll(HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
                    if (o == CLOSED) {
                        return;
                    }
                    if (o == null) {
                        idle.beat();
                        continue;
                    }
                    Entry<T> e = (Entry<T>) o;
                    sink.send(e.id(), e.message());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "Hub subscriber on " + topic.name + " ended", e);
            } finally {
                close();
            }
        }
    }

    private final int queueSize;
    private final int maxSubscribers;
    private final int replay;
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final AtomicInteger subscribers = new AtomicInteger();
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);
    private final AppMetrics.Counter dropped = new AppMetrics.Counter();

    private Hub(int queueSize, int maxSubscribers, int replay) {
        if (queueSize < 1 || maxSubscribers < 1 || replay < 0 || replay > queueSize) {
            throw new IllegalArgumentException(
                    "Need queueSize >= 1, maxSubscribers >= 1 and 0 <= replay <= queueSize");
        }
        this.queueSize = queueSize;
        this.maxSubscribers = maxSubscribers;
        this.replay = replay;
    }

    /** A hub with a 64-message queue per subscriber, at most 10000 subscribers and no replay. */
    public static <T> Hub<T> create() {
        return new Hub<>(64, 10_000, 0);
    }

    /**
     * A hub keeping the last {@code replay} messages per topic for {@code Last-Event-ID} resume.
     *
     * @throws IllegalArgumentException unless {@code queueSize >= 1}, {@code maxSubscribers >= 1}
     *     and {@code 0 <= replay <= queueSize}
     */
    public static <T> Hub<T> create(int queueSize, int maxSubscribers, int replay) {
        return new Hub<>(queueSize, maxSubscribers, replay);
    }

    /**
     * Subscribes {@code sink} on a virtual thread. A numeric {@code lastEventId} (may be {@code
     * null}) first replays the buffered messages after it.
     *
     * @throws HttpException 503 when the hub already has {@code maxSubscribers}
     */
    public Subscription subscribe(String topic, String lastEventId, Sink<T> sink) {
        Subscription sub = register(topic, lastEventId, sink);
        Thread.ofVirtual().name("jolt-hub-" + topic).start(() -> sub.run(null));
        return sub;
    }

    /**
     * Queues {@code message} for every subscriber of {@code topic} and returns how many took it. A
     * subscriber whose queue is full is dropped.
     */
    public int publish(String topic, T message) {
        Objects.requireNonNull(message, "message");
        Topic t = replay > 0 ? topic(topic) : topics.get(Objects.requireNonNull(topic, "topic"));
        if (t == null) {
            return 0;
        }
        int delivered = 0;
        synchronized (t) {
            Entry<T> e = new Entry<>(ids.incrementAndGet(), message);
            if (replay > 0) {
                if (t.recent.size() == replay) {
                    t.recent.removeFirst();
                }
                t.recent.addLast(e);
            }
            for (Iterator<Subscription> it = t.subs.iterator(); it.hasNext(); ) {
                Subscription s = it.next();
                if (s.offer(e)) {
                    delivered++;
                } else if (s.shutdown()) {
                    it.remove();
                    dropped.increment();
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Hub dropped a slow subscriber on " + t.name + " (queue full)");
                }
            }
            removeIfEmpty(t);
        }
        return delivered;
    }

    /**
     * Serves {@code topic} as Server-Sent Events until the client leaves or falls behind: each
     * message is one event with its id, as text for a String and JSON otherwise. Sends a keep-alive
     * comment every 15 s and resumes from {@code req.lastEventId()}.
     *
     * @throws HttpException 503 when the hub already has {@code maxSubscribers}
     */
    public void sse(String topic, Request req, Response res) throws IOException {
        EventStream[] out = new EventStream[1];
        Subscription sub =
                register(
                        topic,
                        req.lastEventId(),
                        (id, m) -> out[0].event(Long.toString(id), null, encode(m, res.mapper())));
        try {
            out[0] = res.sse();
        } catch (IOException | RuntimeException e) {
            sub.close();
            throw e;
        }
        sub.run(() -> out[0].comment(""));
    }

    /**
     * A WebSocket handler that subscribes each session to {@code topic.apply(request)} on open and
     * unsubscribes it on close. Messages go out as text, JSON unless a String; ids are not sent.
     */
    public WebSocketHandler ws(Function<Request, String> topic) {
        Objects.requireNonNull(topic, "topic");
        Map<WebSocketSession, Subscription> open = new ConcurrentHashMap<>();
        return new WebSocketHandler() {
            @Override
            public void onOpen(WebSocketSession session) {
                Request req = session.request();
                open.put(
                        session,
                        subscribe(
                                topic.apply(req),
                                null,
                                (id, m) -> session.send(encode(m, req.mapper()))));
            }

            @Override
            public void onClose(WebSocketSession session, int code, String reason) {
                Subscription sub = open.remove(session);
                if (sub != null) {
                    sub.close();
                }
            }
        };
    }

    /** Current subscribers of {@code topic}. */
    public int subscribers(String topic) {
        Topic t = topics.get(Objects.requireNonNull(topic, "topic"));
        if (t == null) {
            return 0;
        }
        synchronized (t) {
            return t.subs.size();
        }
    }

    /** Current subscribers across all topics. */
    public int subscribers() {
        return subscribers.get();
    }

    /**
     * Registers {@code <name>_subscribers} (gauge) and {@code <name>_dropped_total} (slow
     * subscribers dropped) in {@code metrics}.
     */
    public Hub<T> metrics(AppMetrics metrics, String name) {
        metrics.gauge(name + "_subscribers", "Hub subscribers connected.", this::subscribers);
        metrics.counter(name + "_dropped_total", "Hub subscribers dropped as too slow.", dropped);
        return this;
    }

    private Subscription register(String topic, String lastEventId, Sink<T> sink) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(topic, "topic");
        if (subscribers.incrementAndGet() > maxSubscribers) {
            subscribers.decrementAndGet();
            throw new HttpException(503, "Too many subscribers");
        }
        long after = parseId(lastEventId);
        while (true) {
            Topic t = topic(topic);
            synchronized (t) {
                if (t.removed) {
                    continue;
                }
                Subscription sub = new Subscription(t, sink);
                if (after >= 0) {
                    for (Entry<T> e : t.recent) {
                        if (e.id() > after) {
                            sub.offer(e);
                        }
                    }
                }
                t.subs.add(sub);
                return sub;
            }
        }
    }

    private Topic topic(String name) {
        return topics.computeIfAbsent(Objects.requireNonNull(name, "topic"), Topic::new);
    }

    private void detach(Topic t, Subscription sub) {
        synchronized (t) {
            t.subs.remove(sub);
            removeIfEmpty(t);
        }
    }

    private void removeIfEmpty(Topic t) {
        if (t.subs.isEmpty() && replay == 0 && !t.removed) {
            t.removed = true;
            topics.remove(t.name, t);
        }
    }

    private static long parseId(String id) {
        if (id == null) {
            return -1;
        }
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String encode(Object message, JsonMapper mapper) {
        return message instanceof String s
                ? s
                : new String(mapper.write(message), StandardCharsets.UTF_8);
    }
}
