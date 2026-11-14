package io.github.aayaman.taskflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class PostgresConnectivityIT {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void executesQuery() {
		Integer result = jdbc.sql("select 1").query(Integer.class).single();

		assertThat(result).isEqualTo(1);
	}

	@Test
	void runsPostgresMajorVersion18() {
		Integer versionNumber = jdbc.sql("select current_setting('server_version_num')::int")
			.query(Integer.class)
			.single();

		assertThat(versionNumber / 10_000).isEqualTo(18);
	}

	@Test
	void connectionsDefaultToTaskflowSchema() {
		String schema = jdbc.sql("select current_schema()").query(String.class).single();

		assertThat(schema).isEqualTo("taskflow");
	}

	@Test
	void flywayCreatedTaskflowSchema() {
		Integer schemaCreations = jdbc
			.sql("select count(*) from taskflow.flyway_schema_history where type = 'SCHEMA' and success")
			.query(Integer.class)
			.single();

		assertThat(schemaCreations).isEqualTo(1);
	}

}
