package io.github.aayaman.taskflow.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.aayaman.taskflow.handler.JobContext;
import io.github.aayaman.taskflow.handler.JobHandler;
import io.github.aayaman.taskflow.handler.JobHandlerRegistry;
import io.github.aayaman.taskflow.job.Job;
import io.github.aayaman.taskflow.job.JobQueue;

/**
 * Polls for due jobs and runs them, at most {@code concurrency} at a time.
 * <p>
 * One poller thread claims only as many jobs as there are free slots, so this instance
 * never holds RUNNING jobs it cannot start yet. Each job runs on its own virtual thread;
 * the semaphore, not a thread pool, bounds concurrency. Claiming commits before the
 * handler runs (see JobQueue), so no transaction or connection is held while a job runs.
 * <p>
 * Each start uses a new random worker id: a restarted process is a different worker.
 * Jobs left RUNNING by a crash, a failed outcome write, or a shutdown timeout are not
 * recovered yet; leases and recovery arrive in M3.
 */
class JobWorker implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

	private final JobQueue queue;

	private final JobHandlerRegistry handlers;

	private final WorkerProperties properties;

	private final Semaphore slots;

	private volatile boolean running;

	private UUID workerId;

	private CountDownLatch stopSignal;

	private ExecutorService executor;

	private Thread poller;

	JobWorker(JobQueue queue, JobHandlerRegistry handlers, WorkerProperties properties) {
		this.queue = queue;
		this.handlers = handlers;
		this.properties = properties;
		this.slots = new Semaphore(properties.concurrency());
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		workerId = UUID.randomUUID();
		stopSignal = new CountDownLatch(1);
		executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("taskflow-job-", 0).factory());
		running = true;
		poller = Thread.ofPlatform().name("taskflow-worker-poller").start(this::pollLoop);
		log.info("Worker {} started: concurrency {}, poll interval {}", workerId, properties.concurrency(),
				properties.pollInterval());
	}

	/**
	 * Stops claiming, then waits up to the shutdown timeout for running jobs. Jobs still
	 * running afterwards are abandoned, not interrupted: interrupting a handler mid-way is
	 * no safer than letting the process exit.
	 */
	@Override
	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		stopSignal.countDown();
		Instant deadline = Instant.now().plus(properties.shutdownTimeout());
		try {
			poller.join(remaining(deadline));
			executor.shutdown();
			if (!executor.awaitTermination(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS)) {
				log.warn("Worker {} stopped with {} job(s) still running; they stay RUNNING", workerId,
						properties.concurrency() - slots.availablePermits());
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		log.info("Worker {} stopped", workerId);
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	UUID workerId() {
		return workerId;
	}

	private void pollLoop() {
		while (running) {
			try {
				if (!slots.tryAcquire(properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
					continue;
				}
				int free = 1 + slots.drainPermits();
				List<Job> claimed = claim(free);
				slots.release(free - claimed.size());
				claimed.forEach(this::dispatch);
				if (claimed.size() < free) {
					stopSignal.await(properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private List<Job> claim(int limit) {
		try {
			return queue.claim(workerId, limit);
		}
		catch (RuntimeException ex) {
			log.warn("Worker {} could not claim jobs; retrying after {}", workerId, properties.pollInterval(), ex);
			return List.of();
		}
	}

	private void dispatch(Job job) {
		try {
			executor.execute(() -> {
				try {
					execute(job);
				}
				finally {
					slots.release();
				}
			});
		}
		catch (RejectedExecutionException ex) {
			slots.release();
			log.warn("Worker {} is shutting down; job {} stays RUNNING", workerId, job.id());
		}
	}

	private void execute(Job job) {
		Optional<JobHandler> handler = handlers.find(job.type());
		if (handler.isEmpty()) {
			// Submission rejects unknown types; this covers handlers removed since then.
			record(job, false, "No handler for job type " + job.type());
			return;
		}
		try {
			handler.get().handle(new JobContext(job.id(), job.payload()));
		}
		catch (Exception ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.debug("Job {} failed", job.id(), ex);
			record(job, false, describe(ex));
			return;
		}
		record(job, true, null);
	}

	private void record(Job job, boolean succeeded, @Nullable String error) {
		try {
			boolean recorded = succeeded ? queue.markSucceeded(job.id(), workerId)
					: queue.markFailed(job.id(), workerId, error);
			if (!recorded) {
				log.warn("Job {} is no longer held by worker {}; outcome not recorded", job.id(), workerId);
			}
		}
		catch (RuntimeException ex) {
			log.error("Could not record outcome of job {}; it stays RUNNING", job.id(), ex);
		}
	}

	private static String describe(Exception ex) {
		return (ex.getMessage() != null) ? ex.getClass().getName() + ": " + ex.getMessage() : ex.getClass().getName();
	}

	private static Duration remaining(Instant deadline) {
		Duration left = Duration.between(Instant.now(), deadline);
		return left.isNegative() ? Duration.ZERO : left;
	}

}
