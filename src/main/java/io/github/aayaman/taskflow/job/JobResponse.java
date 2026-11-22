package io.github.aayaman.taskflow.job;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * Public API representation of a job. Kept separate from {@link Job} so that internal
 * fields (such as the worker holding a job) are not exposed by accident.
 */
public record JobResponse(
		UUID id,
		String type,
		JobStatus status,
		JsonNode payload,
		Instant runAt,
		Instant createdAt,
		@Schema(description = "When the most recent attempt started; null until a worker picks the job up")
		@Nullable Instant startedAt,
		@Schema(description = "When the job reached SUCCEEDED or FAILED; null until then")
		@Nullable Instant finishedAt,
		@Schema(description = "Error of the most recent failed attempt, at most 4000 characters; null if none")
		@Nullable String lastError) {

	static JobResponse from(Job job) {
		return new JobResponse(job.id(), job.type(), job.status(), job.payload(), job.runAt(), job.createdAt(),
				job.startedAt(), job.finishedAt(), job.lastError());
	}

}
