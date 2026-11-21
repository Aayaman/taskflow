package io.github.aayaman.taskflow.handler;

/**
 * Executes jobs of one type. Implementations are Spring beans and are discovered by
 * {@link JobHandlerRegistry}.
 * <p>
 * Delivery is at-least-once: a job can be handled more than once, for example after a
 * worker crash. Handlers must therefore be safe to retry; the job id is a natural
 * idempotency key for side effects in other systems.
 */
public interface JobHandler {

	/**
	 * Job types are UPPER_SNAKE_CASE, at most 100 characters. Mirrors the
	 * {@code jobs_type_format} database constraint.
	 */
	String TYPE_PATTERN = "[A-Z][A-Z0-9]*(_[A-Z0-9]+)*";

	int TYPE_MAX_LENGTH = 100;

	/**
	 * The job type this handler executes. Must match {@link #TYPE_PATTERN}.
	 */
	String type();

	/**
	 * Executes the job. Returning normally means success; throwing means the attempt failed.
	 */
	void handle(JobContext job) throws Exception;

}
