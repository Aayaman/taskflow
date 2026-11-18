package io.github.aayaman.taskflow.job;

/**
 * Lifecycle states of a job and the transitions allowed between them.
 * <p>
 * This type defines which transitions are legal. It does not make them safe under
 * concurrency on its own: persisted transitions must also be conditional on the
 * current state in the database (for example {@code UPDATE ... WHERE status = 'PENDING'}),
 * so that only one of several competing updates can succeed.
 */
public enum JobStatus {

	/** Waiting to run. The job's {@code run_at} time says when it becomes eligible. */
	PENDING,

	/** Claimed by a worker that holds a lease on it. */
	RUNNING,

	/** Finished successfully. */
	SUCCEEDED,

	/** Failed permanently, or retries were exhausted. Can only leave this state by a manual retry. */
	FAILED,

	/** Cancelled before it started running. */
	CANCELLED;

	/**
	 * Returns whether a job in this state may move to {@code target}.
	 * Staying in the same state is not a transition and is never allowed.
	 */
	public boolean canTransitionTo(JobStatus target) {
		return switch (this) {
			case PENDING -> target == RUNNING || target == CANCELLED;
			// back to PENDING: retry after a failed attempt, or recovery after a lease expired
			case RUNNING -> target == SUCCEEDED || target == FAILED || target == PENDING;
			// manual retry by an operator
			case FAILED -> target == PENDING;
			case SUCCEEDED, CANCELLED -> false;
		};
	}

	/**
	 * Returns whether the system will never move a job out of this state on its own.
	 * {@link #FAILED} is terminal even though an operator may retry it manually.
	 */
	public boolean isTerminal() {
		return switch (this) {
			case SUCCEEDED, FAILED, CANCELLED -> true;
			case PENDING, RUNNING -> false;
		};
	}

}
