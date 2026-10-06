package com.ticketfactory.queue;

import com.ticketfactory.FactoryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@code factory.worker.threads} polling workers, each on its own virtual thread. */
@Component
public class WorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final JobQueue queue;
    private final ObjectProvider<JobHandler> handler;
    private final FactoryProperties.Worker config;
    private final com.ticketfactory.metrics.FactoryMetrics metrics;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private volatile boolean running;
    private ExecutorService executor;

    public WorkerPool(JobQueue queue, ObjectProvider<JobHandler> handler, FactoryProperties props,
                      com.ticketfactory.metrics.FactoryMetrics metrics) {
        this.metrics = metrics;
        this.queue = queue;
        this.handler = handler;
        this.config = props.worker();
    }

    /** Builds a worker bound to this pool's handler. Tests use this to drive work deterministically. */
    public Worker newWorker(String name) {
        return new Worker(instanceId + "-" + name, queue, handler.getObject(), config, metrics);
    }

    @Override
    public void start() {
        if (!config.enabled() || handler.getIfAvailable() == null) {
            log.info("Worker pool disabled");
            return;
        }
        running = true;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < config.threads(); i++) {
            workers.add(newWorker("w" + i));
        }
        workers.forEach(w -> executor.submit(() -> loop(w)));
        log.info("Started {} workers", workers.size());
    }

    private void loop(Worker worker) {
        while (running) {
            try {
                if (!worker.runOnce()) {
                    Thread.sleep(config.pollInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Worker {} loop error", worker.id(), e);
                sleepQuietly();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(config.pollInterval());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Recovers jobs whose worker died mid-run. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void reapExpiredLeases() {
        if (!running) {
            return;
        }
        int recovered = queue.releaseExpiredLeases(config.leaseTimeout());
        if (recovered > 0) {
            log.warn("Recovered {} jobs with expired leases", recovered);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
