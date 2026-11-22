package io.github.aayaman.taskflow.job;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.aayaman.taskflow.job.internal.JobRepository;

/**
 * The job module's API for workers: claiming jobs and, later, recording their outcome.
 * Workers use this instead of the jobs table, which belongs to this module.
 */
@Service
public class JobQueue {

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

}
