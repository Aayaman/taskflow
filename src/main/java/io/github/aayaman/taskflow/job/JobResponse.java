package io.github.aayaman.taskflow.job;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Public API representation of a job. Kept separate from {@link Job} so that internal
 * fields added later (for example lease information) are not exposed by accident.
 */
public record JobResponse(
		UUID id,
		String type,
		JobStatus status,
		JsonNode payload,
		Instant runAt,
		Instant createdAt) {

	static JobResponse from(Job job) {
		return new JobResponse(job.id(), job.type(), job.status(), job.payload(), job.runAt(), job.createdAt());
	}

}
