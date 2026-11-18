package io.github.aayaman.taskflow.job.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import io.github.aayaman.taskflow.job.IdempotencyKeyReusedException;
import io.github.aayaman.taskflow.job.Job;
import tools.jackson.databind.JsonNode;

@Service
public class JobService {

	private final JobRepository jobs;

	JobService(JobRepository jobs) {
		this.jobs = jobs;
	}

	/**
	 * Accepts a validated job. It is persisted as PENDING and eligible to run immediately.
	 * <p>
	 * With an idempotency key, a repeated submission returns the original job instead of
	 * creating a new one, and reusing the key for a different request is rejected.
	 * <p>
	 * Deliberately not {@code @Transactional}: each statement runs in its own READ COMMITTED
	 * transaction. When the insert reports a conflict, the competing insert has committed,
	 * and the lookup, as a new statement, is guaranteed to see it. Inside a single
	 * REPEATABLE READ transaction the lookup would use an older snapshot and miss the row.
	 * @throws IdempotencyKeyReusedException if the key belongs to a different request
	 */
	public Submission submit(String type, JsonNode payload, @Nullable String idempotencyKey) {
		Optional<Job> inserted = jobs.insert(type, payload, idempotencyKey);
		if (inserted.isPresent()) {
			return new Submission(inserted.get(), true);
		}
		JobRepository.KeyedJob existing = jobs.findByIdempotencyKey(idempotencyKey, type, payload)
			.orElseThrow(() -> new IllegalStateException("Idempotency key conflict reported, but no job holds the key"));
		if (!existing.sameRequest()) {
			throw new IdempotencyKeyReusedException();
		}
		return new Submission(existing.job(), false);
	}

	public Optional<Job> find(UUID id) {
		return jobs.findById(id);
	}

	/**
	 * The outcome of a submission: the job, and whether this call created it
	 * ({@code false} when an idempotent retry returned an existing job).
	 */
	public record Submission(Job job, boolean created) {
	}

}
