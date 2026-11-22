package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.aayaman.taskflow.TestcontainersConfiguration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class JobQueueIT {

	private static final UUID WORKER = UUID.randomUUID();

	@Autowired
	private JobQueue queue;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	/**
	 * Claiming takes any due job in the table, so each test starts from an empty queue.
	 */
	@BeforeEach
	void emptyQueue() {
		jdbc.sql("delete from jobs").update();
	}

	@Test
	void claimMovesDueJobToRunningForTheWorker() {
		UUID id = insertJob("-1 minute");

		List<Job> claimed = queue.claim(WORKER, 10);

		assertThat(claimed).singleElement().satisfies(job -> {
			assertThat(job.id()).isEqualTo(id);
			assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
			assertThat(job.workerId()).isEqualTo(WORKER);
			assertThat(job.startedAt()).isNotNull();
		});
		assertThat(statusOf(id)).isEqualTo("RUNNING");
	}

	@Test
	void doesNotClaimJobsThatAreNotDueYet() {
		insertJob("+1 hour");

		assertThat(queue.claim(WORKER, 10)).isEmpty();
	}

	@Test
	void doesNotClaimJobsThatAreNotPending() {
		UUID id = insertJob("-1 minute");
		queue.claim(WORKER, 10);

		assertThat(queue.claim(UUID.randomUUID(), 10)).isEmpty();
		assertThat(workerOf(id)).isEqualTo(WORKER);
	}

	@Test
	void claimsOldestDueJobsFirstUpToTheLimit() {
		UUID oldest = insertJob("-3 minutes");
		UUID middle = insertJob("-2 minutes");
		insertJob("-1 minute");

		List<Job> claimed = queue.claim(WORKER, 2);

		assertThat(claimed).extracting(Job::id).containsExactly(oldest, middle);
	}

	@Test
	void returnsEmptyListWhenNothingIsDue() {
		assertThat(queue.claim(WORKER, 5)).isEmpty();
	}

	@Test
	void rejectsLimitBelowOne() {
		assertThatIllegalArgumentException().isThrownBy(() -> queue.claim(WORKER, 0));
	}

	/**
	 * Another transaction holds a lock on the oldest job, as a slower concurrent claimer
	 * would. The claim must skip that row instead of waiting for it. The claim runs on its
	 * own thread and the lock is always released, so a regression fails instead of hanging.
	 */
	@Test
	void skipsJobsLockedByAnotherTransaction() throws Exception {
		UUID locked = insertJob("-2 minutes");
		UUID free = insertJob("-1 minute");

		List<Job> claimed;
		try (Connection other = dataSource.getConnection();
				ExecutorService claimer = Executors.newSingleThreadExecutor()) {
			other.setAutoCommit(false);
			try (PreparedStatement lock = other.prepareStatement("select id from jobs where id = ? for update")) {
				lock.setObject(1, locked);
				lock.executeQuery().close();
			}
			Future<List<Job>> claim = claimer.submit(() -> queue.claim(WORKER, 10));
			try {
				claimed = claim.get(5, TimeUnit.SECONDS);
			}
			finally {
				other.rollback();
			}
		}

		assertThat(claimed).extracting(Job::id).containsExactly(free);
		assertThat(statusOf(locked)).isEqualTo("PENDING");
	}

	@Test
	void concurrentClaimersNeverClaimTheSameJob() throws Exception {
		int jobs = 300;
		int claimers = 12;
		jdbc.sql("insert into jobs (type, payload, run_at) select 'SEND_EMAIL', '{}', now() - interval '1 minute' "
				+ "from generate_series(1, :n)")
			.param("n", jobs)
			.update();

		CountDownLatch start = new CountDownLatch(1);
		List<UUID> claimed = Collections.synchronizedList(new ArrayList<>());
		try (ExecutorService pool = Executors.newFixedThreadPool(claimers)) {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < claimers; i++) {
				UUID worker = UUID.randomUUID();
				futures.add(pool.submit(() -> {
					start.await();
					List<Job> batch;
					do {
						batch = queue.claim(worker, 5);
						batch.forEach(job -> claimed.add(job.id()));
					}
					while (!batch.isEmpty());
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		}

		assertThat(claimed).hasSize(jobs).doesNotHaveDuplicates();
		assertThat(jdbc.sql("select count(*) from jobs where status = 'RUNNING'").query(Integer.class).single())
			.isEqualTo(jobs);
	}

	@Test
	void ownerRecordsSuccess() {
		UUID id = claimOne();

		assertThat(queue.markSucceeded(id, WORKER)).isTrue();

		Map<String, Object> row = row(id);
		assertThat(row.get("status")).isEqualTo("SUCCEEDED");
		assertThat(row.get("finished_at")).isNotNull();
	}

	@Test
	void ownerRecordsFailureWithError() {
		UUID id = claimOne();

		assertThat(queue.markFailed(id, WORKER, "java.lang.IllegalStateException: boom")).isTrue();

		Map<String, Object> row = row(id);
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("finished_at")).isNotNull();
		assertThat(row.get("last_error")).isEqualTo("java.lang.IllegalStateException: boom");
	}

	@Test
	void storesLongErrorsTruncated() {
		UUID id = claimOne();

		assertThat(queue.markFailed(id, WORKER, "e".repeat(JobQueue.MAX_ERROR_LENGTH + 500))).isTrue();

		assertThat((String) row(id).get("last_error")).hasSize(JobQueue.MAX_ERROR_LENGTH);
	}

	@Test
	void otherWorkerCannotRecordOutcome() {
		UUID id = claimOne();
		UUID other = UUID.randomUUID();

		assertThat(queue.markSucceeded(id, other)).isFalse();
		assertThat(queue.markFailed(id, other, "boom")).isFalse();
		assertThat(statusOf(id)).isEqualTo("RUNNING");
	}

	@Test
	void outcomeIsRecordedOnlyOnce() {
		UUID id = claimOne();
		queue.markSucceeded(id, WORKER);

		assertThat(queue.markFailed(id, WORKER, "late failure")).isFalse();
		assertThat(statusOf(id)).isEqualTo("SUCCEEDED");
	}

	@Test
	void pendingJobCannotBeCompleted() {
		UUID id = insertJob("-1 minute");

		assertThat(queue.markSucceeded(id, WORKER)).isFalse();
		assertThat(statusOf(id)).isEqualTo("PENDING");
	}

	private UUID claimOne() {
		UUID id = insertJob("-1 minute");
		assertThat(queue.claim(WORKER, 1)).extracting(Job::id).containsExactly(id);
		return id;
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.sql("select status, finished_at, last_error from jobs where id = :id").param("id", id).query().singleRow();
	}

	private UUID insertJob(String runAtOffset) {
		return jdbc.sql("insert into jobs (type, payload, run_at) values ('SEND_EMAIL', '{}', now() + cast(:offset as interval)) returning id")
			.param("offset", runAtOffset)
			.query(UUID.class)
			.single();
	}

	private String statusOf(UUID id) {
		return jdbc.sql("select status from jobs where id = :id").param("id", id).query(String.class).single();
	}

	private UUID workerOf(UUID id) {
		return jdbc.sql("select worker_id from jobs where id = :id").param("id", id).query(UUID.class).single();
	}

}
