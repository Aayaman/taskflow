package io.github.aayaman.taskflow.job;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * A job as stored in the database.
 */
public record Job(
		UUID id,
		String type,
		JsonNode payload,
		JobStatus status,
		Instant runAt,
		Instant createdAt,
		Instant updatedAt,
		@Nullable UUID workerId,
		@Nullable Instant startedAt,
		@Nullable Instant finishedAt,
		@Nullable String lastError) {
}
