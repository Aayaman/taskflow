package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.aayaman.taskflow.TestcontainersConfiguration;

/**
 * Verifies the constraints of the jobs table itself, by writing rows directly with SQL
 * and bypassing the application's own validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class JobsTableIT {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void generatesTimeOrderedUuidV7Ids() {
		UUID id = insert("SEND_EMAIL", "{}", null);

		assertThat(id.version()).isEqualTo(7);
		// The first 48 bits of a UUIDv7 are its creation time in Unix milliseconds.
		Instant embedded = Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
		assertThat(Duration.between(embedded, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
	}

	@Test
	void appliesDefaults() {
		UUID id = insert("SEND_EMAIL", "{\"to\": \"someone\"}", null);

		Map<String, Object> row = jdbc.sql("select status, run_at, created_at, updated_at from jobs where id = :id")
			.param("id", id)
			.query()
			.singleRow();
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("run_at")).isNotNull();
		assertThat(row.get("created_at")).isNotNull();
		assertThat(row.get("updated_at")).isNotNull();
	}

	@Test
	void rejectsUnknownStatus() {
		assertRejected("insert into jobs (type, payload, status) values ('SEND_EMAIL', '{}', 'QUEUED')",
				"jobs_status_valid");
	}

	@ParameterizedTest
	@ValueSource(strings = { "send_email", "SEND-EMAIL", "_SEND", "SEND_", "SEND__EMAIL", "1SEND", "" })
	void rejectsMalformedType(String type) {
		assertRejected("insert into jobs (type, payload) values ('" + type + "', '{}')", "jobs_type_format");
	}

	@Test
	void rejectsTypeLongerThan100Characters() {
		assertRejected("insert into jobs (type, payload) values ('" + "A".repeat(101) + "', '{}')", "jobs_type_format");
	}

	@ParameterizedTest
	@ValueSource(strings = { "[]", "\"text\"", "42", "null" })
	void rejectsPayloadThatIsNotAnObject(String payload) {
		assertRejected("insert into jobs (type, payload) values ('SEND_EMAIL', '" + payload + "')",
				"jobs_payload_is_object");
	}

	@Test
	void rejectsDuplicateIdempotencyKey() {
		String key = "key-" + UUID.randomUUID();
		insert("SEND_EMAIL", "{}", key);

		assertRejected("insert into jobs (type, payload, idempotency_key) values ('SEND_EMAIL', '{}', '" + key + "')",
				"jobs_idempotency_key_unique");
	}

	@Test
	void allowsManyJobsWithoutIdempotencyKey() {
		UUID first = insert("SEND_EMAIL", "{}", null);
		UUID second = insert("SEND_EMAIL", "{}", null);

		assertThat(first).isNotEqualTo(second);
	}

	@Test
	void rejectsEmptyOrOversizedIdempotencyKey() {
		assertRejected("insert into jobs (type, payload, idempotency_key) values ('SEND_EMAIL', '{}', '')",
				"jobs_idempotency_key_length");
		assertRejected("insert into jobs (type, payload, idempotency_key) values ('SEND_EMAIL', '{}', '"
				+ "k".repeat(256) + "')", "jobs_idempotency_key_length");
	}

	private UUID insert(String type, String payload, String idempotencyKey) {
		return jdbc.sql("""
				insert into jobs (type, payload, idempotency_key)
				values (:type, cast(:payload as jsonb), :key)
				returning id""")
			.param("type", type)
			.param("payload", payload)
			.param("key", idempotencyKey)
			.query(UUID.class)
			.single();
	}

	private void assertRejected(String sql, String constraint) {
		assertThatThrownBy(() -> jdbc.sql(sql).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

}
