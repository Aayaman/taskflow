package io.github.aayaman.taskflow.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.aayaman.taskflow.handler.JobHandlerRegistry;
import io.github.aayaman.taskflow.job.JobQueue;

class WorkerConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(WorkerConfiguration.class)
		.withBean(JobQueue.class, () -> mock(JobQueue.class))
		.withBean(JobHandlerRegistry.class, () -> new JobHandlerRegistry(List.of()));

	@Test
	void workerIsEnabledByDefault() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(JobWorker.class);
			assertThat(context.getBean(WorkerProperties.class).concurrency()).isEqualTo(4);
		});
	}

	@Test
	void workerCanBeDisabledForApiOnlyInstances() {
		runner.withPropertyValues("taskflow.worker.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(JobWorker.class));
	}

	@Test
	void rejectsConcurrencyBelowOne() {
		runner.withPropertyValues("taskflow.worker.concurrency=0")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("taskflow.worker.concurrency must be at least 1"));
	}

	@Test
	void rejectsNonPositivePollInterval() {
		runner.withPropertyValues("taskflow.worker.poll-interval=0s")
			.run(context -> assertThat(context).hasFailed());
	}

}
