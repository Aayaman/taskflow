package io.github.aayaman.taskflow.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the worker role, under {@code taskflow.worker}.
 *
 * @param enabled whether this instance runs jobs; API-only instances set it to false
 * @param concurrency maximum number of jobs this instance runs at the same time
 * @param pollInterval how long to wait before polling again when no job was due
 * @param shutdownTimeout how long shutdown waits for running jobs before giving up
 */
@ConfigurationProperties("taskflow.worker")
public record WorkerProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("4") int concurrency,
		@DefaultValue("1s") Duration pollInterval,
		@DefaultValue("30s") Duration shutdownTimeout) {

	public WorkerProperties {
		if (concurrency < 1) {
			throw new IllegalArgumentException("taskflow.worker.concurrency must be at least 1, was " + concurrency);
		}
		if (pollInterval.isNegative() || pollInterval.isZero()) {
			throw new IllegalArgumentException("taskflow.worker.poll-interval must be positive, was " + pollInterval);
		}
		if (shutdownTimeout.isNegative()) {
			throw new IllegalArgumentException("taskflow.worker.shutdown-timeout must not be negative, was " + shutdownTimeout);
		}
	}

}
