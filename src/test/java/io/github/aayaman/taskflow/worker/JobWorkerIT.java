package io.github.aayaman.taskflow.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

import io.github.aayaman.taskflow.TestcontainersConfiguration;
import io.github.aayaman.taskflow.handler.JobContext;
import io.github.aayaman.taskflow.handler.JobHandler;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
		"taskflow.worker.enabled=true",
		"taskflow.worker.concurrency=3",
		"taskflow.worker.poll-interval=100ms",
		"taskflow.worker.shutdown-timeout=2s" })
@Import({ TestcontainersConfiguration.class, JobWorkerIT.Handlers.class })
class JobWorkerIT {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JobWorker worker;

	@Autowired
	private Handlers handlers;

	@BeforeEach
	void reset() {
		handlers.reset();
		jdbc.sql("delete from jobs").update();
	}

	@AfterEach
	void releaseBlockedJobs() {
		handlers.gate.get().countDown();
		await().atMost(TIMEOUT).until(() -> handlers.active.get() == 0);
	}

	@Test
	void runsJobAndRecordsSuccess() {
		UUID id = insert("WORKER_SUCCEED", "{\"n\": 1}");

		awaitStatus(id, "SUCCEEDED");

		assertThat(row(id).get("finished_at")).isNotNull();
		assertThat(handlers.succeeded).singleElement().satisfies(job -> {
			assertThat(job.id()).isEqualTo(id);
			assertThat(job.payload().path("n").asInt()).isEqualTo(1);
		});
	}

	@Test
	void recordsHandlerFailure() {
		UUID id = insert("WORKER_FAIL", "{}");

		awaitStatus(id, "FAILED");

		assertThat(row(id).get("last_error")).isEqualTo("java.lang.IllegalStateException: boom");
	}

	@Test
	void failsJobWhoseTypeHasNoHandler() {
		UUID id = insert("NO_HANDLER_TYPE", "{}");

		awaitStatus(id, "FAILED");

		assertThat(row(id).get("last_error")).isEqualTo("No handler for job type NO_HANDLER_TYPE");
	}

	@Test
	void runsAtMostConcurrencyJobsAndClaimsOnlyFreeSlots() {
		for (int i = 0; i < 8; i++) {
			insert("WORKER_BLOCK", "{}");
		}

		await().atMost(TIMEOUT).until(() -> handlers.active.get() == 3);
		// Several poll intervals pass: the worker must neither start nor claim more jobs.
		await().during(Duration.ofMillis(600)).atMost(TIMEOUT)
			.until(() -> handlers.active.get() == 3 && count("RUNNING") == 3 && count("PENDING") == 5);

		handlers.gate.get().countDown();

		await().atMost(TIMEOUT).until(() -> count("SUCCEEDED") == 8);
		assertThat(handlers.maxActive.get()).isEqualTo(3);
	}

	@Test
	@DirtiesContext
	void stopWaitsForRunningJobsAndStopsClaiming() throws Exception {
		UUID running = insert("WORKER_BLOCK", "{}");
		await().atMost(TIMEOUT).until(() -> handlers.active.get() == 1);

		CompletableFuture<Void> stopped = CompletableFuture.runAsync(worker::stop);
		await().atMost(TIMEOUT).until(() -> !worker.isRunning());
		UUID late = insert("WORKER_SUCCEED", "{}");

		await().during(Duration.ofMillis(500)).atMost(TIMEOUT).until(() -> !stopped.isDone());
		handlers.gate.get().countDown();
		stopped.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

		assertThat(row(running).get("status")).isEqualTo("SUCCEEDED");
		assertThat(row(late).get("status")).isEqualTo("PENDING");
	}

	@Test
	@DirtiesContext
	void stopGivesUpAfterShutdownTimeout() throws Exception {
		UUID stuck = insert("WORKER_BLOCK", "{}");
		await().atMost(TIMEOUT).until(() -> handlers.active.get() == 1);

		long start = System.nanoTime();
		CompletableFuture.runAsync(worker::stop).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		Duration took = Duration.ofNanos(System.nanoTime() - start);

		assertThat(took).isBetween(Duration.ofMillis(1500), Duration.ofSeconds(5));
		assertThat(row(stuck).get("status")).isEqualTo("RUNNING");
	}

	private UUID insert(String type, String payload) {
		return jdbc.sql("insert into jobs (type, payload) values (:type, cast(:payload as jsonb)) returning id")
			.param("type", type)
			.param("payload", payload)
			.query(UUID.class)
			.single();
	}

	private void awaitStatus(UUID id, String status) {
		await().atMost(TIMEOUT).until(() -> status.equals(row(id).get("status")));
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.sql("select status, finished_at, last_error from jobs where id = :id").param("id", id).query().singleRow();
	}

	private int count(String status) {
		return jdbc.sql("select count(*) from jobs where status = :status").param("status", status).query(Integer.class).single();
	}

	/**
	 * Handlers whose behaviour the tests control and observe.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class Handlers {

		final Queue<JobContext> succeeded = new ConcurrentLinkedQueue<>();

		final AtomicInteger active = new AtomicInteger();

		final AtomicInteger maxActive = new AtomicInteger();

		final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));

		void reset() {
			succeeded.clear();
			maxActive.set(0);
			gate.set(new CountDownLatch(1));
		}

		@Bean
		JobHandler succeedingHandler() {
			return handler("WORKER_SUCCEED", succeeded::add);
		}

		@Bean
		JobHandler failingHandler() {
			return handler("WORKER_FAIL", job -> {
				throw new IllegalStateException("boom");
			});
		}

		@Bean
		JobHandler blockingHandler() {
			return handler("WORKER_BLOCK", job -> {
				maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
				try {
					gate.get().await();
				}
				finally {
					active.decrementAndGet();
				}
			});
		}

		private static JobHandler handler(String type, Body body) {
			return new JobHandler() {

				@Override
				public String type() {
					return type;
				}

				@Override
				public void handle(JobContext job) throws Exception {
					body.run(job);
				}

			};
		}

		@FunctionalInterface
		interface Body {

			void run(JobContext job) throws Exception;

		}

	}

}
