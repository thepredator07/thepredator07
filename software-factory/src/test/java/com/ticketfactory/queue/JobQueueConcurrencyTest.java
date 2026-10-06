package com.ticketfactory.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.ticket.TicketService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Proves that concurrent workers never claim or process the same job/ticket twice. */
class JobQueueConcurrencyTest extends AbstractIntegrationTest {

    private static final int JOBS = 300;
    private static final int WORKERS = 16;

    @Autowired
    JobQueue queue;

    @Autowired
    TicketService tickets;

    @Autowired
    FactoryProperties props;

    private void seedJobs() {
        for (int i = 1; i <= JOBS; i++) {
            long t = tickets.receive("acme/app", i, "Issue " + i, "").orElseThrow();
            queue.enqueue(t);
        }
    }

    @Test
    void parallelClaimsNeverReturnTheSameJob() throws Exception {
        seedJobs();
        Queue<Long> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                String id = "w" + w;
                futures.add(pool.submit(() -> {
                    start.await();
                    queue.claim(id).ifPresent(j -> claimed.add(j.id()));
                    for (var j = queue.claim(id); j.isPresent(); j = queue.claim(id)) {
                        claimed.add(j.get().id());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        Set<Long> unique = new HashSet<>(claimed);
        assertThat(claimed).hasSize(JOBS);
        assertThat(unique).hasSize(JOBS);
        assertThat(queue.countByStatus(JobStatus.RUNNING)).isEqualTo(JOBS);
        assertThat(queue.countByStatus(JobStatus.PENDING)).isZero();
    }

    @Test
    void parallelWorkersProcessEveryTicketExactlyOnce() throws Exception {
        seedJobs();
        Map<Long, AtomicInteger> runsPerTicket = new ConcurrentHashMap<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        JobHandler slowHandler = (job, ctx) -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            runsPerTicket.computeIfAbsent(job.ticketId(), k -> new AtomicInteger()).incrementAndGet();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return JobOutcome.complete();
        };

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                Worker worker = new Worker("w" + w, queue, slowHandler, props.worker());
                futures.add(pool.submit(() -> {
                    start.await();
                    return worker.drain(Integer.MAX_VALUE);
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> f : futures) {
                total += f.get(60, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(JOBS);
        }

        assertThat(runsPerTicket).hasSize(JOBS);
        assertThat(runsPerTicket.values()).allSatisfy(c -> assertThat(c.get()).isEqualTo(1));
        assertThat(maxInFlight.get()).as("work actually ran in parallel").isGreaterThan(1);
        assertThat(queue.countByStatus(JobStatus.DONE)).isEqualTo(JOBS);
    }
}
