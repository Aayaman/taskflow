package io.github.aayaman.taskflow.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.aayaman.taskflow.handler.JobHandlerRegistry;
import io.github.aayaman.taskflow.job.JobQueue;

/**
 * Runs this instance in the worker role unless {@code taskflow.worker.enabled=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "taskflow.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
	JobWorker jobWorker(JobQueue queue, JobHandlerRegistry handlers, WorkerProperties properties) {
		return new JobWorker(queue, handlers, properties);
	}

}
