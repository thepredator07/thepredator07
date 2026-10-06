package com.ticketfactory.pipeline;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PipelineConfig {

    /** Runs agent calls so the pipeline can time them out. Closed (interrupting running calls) on shutdown. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
