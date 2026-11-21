package io.github.aayaman.taskflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JobHandlerRegistryTest {

	@Test
	void findsHandlerByType() {
		JobHandler email = handler("SEND_EMAIL");
		JobHandlerRegistry registry = new JobHandlerRegistry(List.of(email, handler("RESIZE_IMAGE")));

		assertThat(registry.find("SEND_EMAIL")).containsSame(email);
		assertThat(registry.supports("RESIZE_IMAGE")).isTrue();
		assertThat(registry.types()).containsExactly("RESIZE_IMAGE", "SEND_EMAIL");
	}

	@Test
	void unknownTypeHasNoHandler() {
		JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler("SEND_EMAIL")));

		assertThat(registry.find("SEND_SMS")).isEmpty();
		assertThat(registry.supports("SEND_SMS")).isFalse();
	}

	@Test
	void allowsNoHandlers() {
		assertThat(new JobHandlerRegistry(List.of()).types()).isEmpty();
	}

	@Test
	void rejectsTwoHandlersForTheSameType() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new JobHandlerRegistry(List.of(handler("SEND_EMAIL"), handler("SEND_EMAIL"))))
			.withMessageContaining("Job type 'SEND_EMAIL' has two handlers");
	}

	@ParameterizedTest
	@ValueSource(strings = { "send_email", "SEND-EMAIL", "_SEND", "" })
	void rejectsInvalidType(String type) {
		assertThatIllegalStateException()
			.isThrownBy(() -> new JobHandlerRegistry(List.of(handler(type))))
			.withMessageContaining("declares invalid type '" + type + "'");
	}

	@Test
	void rejectsTypeLongerThan100Characters() {
		assertThatIllegalStateException()
			.isThrownBy(() -> new JobHandlerRegistry(List.of(handler("A".repeat(101)))));
	}

	@Test
	void rejectsMissingType() {
		assertThatIllegalStateException().isThrownBy(() -> new JobHandlerRegistry(List.of(handler(null))));
	}

	@Test
	void duplicateHandlerBeansFailApplicationStartup() {
		new ApplicationContextRunner()
			.withBean("first", JobHandler.class, () -> handler("SEND_EMAIL"))
			.withBean("second", JobHandler.class, () -> handler("SEND_EMAIL"))
			.withBean(JobHandlerRegistry.class)
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Job type 'SEND_EMAIL' has two handlers"));
	}

	private static JobHandler handler(String type) {
		return new JobHandler() {

			@Override
			public String type() {
				return type;
			}

			@Override
			public void handle(JobContext job) {
			}

		};
	}

}
