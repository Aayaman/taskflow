package io.github.aayaman.taskflow.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.aayaman.taskflow.job.Job;
import io.github.aayaman.taskflow.job.JobStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JobRepository {

	private static final String COLUMNS = "id, type, payload::text as payload, status, run_at, created_at, updated_at";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	JobRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Inserts a new job. The database assigns the id, initial status and timestamps.
	 * <p>
	 * Returns empty if the idempotency key is already taken. If a concurrent insert holds
	 * the same key, PostgreSQL waits for it to commit or roll back before deciding, so
	 * exactly one insert per key succeeds. A {@code null} key never conflicts.
	 */
	public Optional<Job> insert(String type, JsonNode payload, @Nullable String idempotencyKey) {
		return jdbc.sql("""
				insert into jobs (type, payload, idempotency_key)
				values (:type, cast(:payload as jsonb), :key)
				on conflict (idempotency_key) do nothing
				returning\s""" + COLUMNS)
			.param("type", type)
			.param("payload", json.writeValueAsString(payload))
			.param("key", idempotencyKey)
			.query(this::mapRow)
			.optional();
	}

	/**
	 * Finds the job holding an idempotency key, and whether it was created from the same
	 * type and payload. Payloads are compared with jsonb equality, so key order and
	 * whitespace do not matter.
	 */
	public Optional<KeyedJob> findByIdempotencyKey(String idempotencyKey, String type, JsonNode payload) {
		return jdbc.sql("select " + COLUMNS + """
				, (type = :type and payload = cast(:payload as jsonb)) as same_request
				from jobs where idempotency_key = :key""")
			.param("type", type)
			.param("payload", json.writeValueAsString(payload))
			.param("key", idempotencyKey)
			.query((rs, rowNum) -> new KeyedJob(mapRow(rs, rowNum), rs.getBoolean("same_request")))
			.optional();
	}

	public record KeyedJob(Job job, boolean sameRequest) {
	}

	public Optional<Job> findById(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from jobs where id = :id")
			.param("id", id)
			.query(this::mapRow)
			.optional();
	}

	private Job mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new Job(
				rs.getObject("id", UUID.class),
				rs.getString("type"),
				json.readTree(rs.getString("payload")),
				JobStatus.valueOf(rs.getString("status")),
				rs.getObject("run_at", OffsetDateTime.class).toInstant(),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

}
