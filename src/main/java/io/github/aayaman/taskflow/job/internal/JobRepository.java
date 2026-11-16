package io.github.aayaman.taskflow.job.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

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
	 */
	public Job insert(String type, JsonNode payload) {
		return jdbc.sql("insert into jobs (type, payload) values (:type, cast(:payload as jsonb)) returning " + COLUMNS)
			.param("type", type)
			.param("payload", json.writeValueAsString(payload))
			.query(this::mapRow)
			.single();
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
