package io.github.aayaman.taskflow.job;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.aayaman.taskflow.job.internal.JobRepository;

/**
 * The job module's API for workers: claiming jobs and recording their outcome.
 * Workers use this instead of the jobs table, which belongs to this module.
 */
@Service
public class JobQueue {

	/**
	 * Longest stored error message, in characters. Matches the {@code jobs_last_error_length}
	 * database constraint.
	 */
	public static final int MAX_ERROR_LENGTH = 4000;

	private final JobRepository jobs;

	JobQueue(JobRepository jobs) {
		this.jobs = jobs;
	}

	/**
	 * Atomically moves up to {@code limit} due PENDING jobs to RUNNING for this worker and
	 * returns them, oldest first. Concurrent callers never receive the same job. Returns an
	 * empty list when nothing is due.
	 */
	public List<Job> claim(UUID workerId, int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1, was " + limit);
		}
		return jobs.claim(workerId, limit);
	}

	/**
	 * Records that the worker finished the job successfully.
	 * <p>
	 * Only the worker holding a RUNNING job can record its outcome. Returns {@code false},
	 * changing nothing, if the job is no longer held by this worker; from M3 on that
	 * happens when its lease expired and the job was handed to another worker.
	 */
	public boolean markSucceeded(UUID id, UUID workerId) {
		return jobs.markSucceeded(id, workerId);
	}

	/**
	 * Records that the job failed, storing {@code error} truncated to
	 * {@link #MAX_ERROR_LENGTH} characters. Same ownership rule as
	 * {@link #markSucceeded(UUID, UUID)}.
	 */
	public boolean markFailed(UUID id, UUID workerId, String error) {
		return jobs.markFailed(id, workerId, truncate(error));
	}

	/**
	 * Cuts by code points, like PostgreSQL's char_length, so a character outside the Basic
	 * Multilingual Plane is never split in half.
	 */
	static String truncate(String error) {
		if (error.codePointCount(0, error.length()) <= MAX_ERROR_LENGTH) {
			return error;
		}
		return error.substring(0, error.offsetByCodePoints(0, MAX_ERROR_LENGTH));
	}

}
