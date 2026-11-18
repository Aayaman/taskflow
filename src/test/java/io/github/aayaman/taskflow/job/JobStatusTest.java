package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class JobStatusTest {

	/** The agreed lifecycle, written independently of the implementation. */
	private static final Set<String> ALLOWED = Set.of(
			"PENDING->RUNNING",
			"PENDING->CANCELLED",
			"RUNNING->SUCCEEDED",
			"RUNNING->FAILED",
			"RUNNING->PENDING",
			"FAILED->PENDING");

	static Stream<Arguments> allPairs() {
		return Arrays.stream(JobStatus.values())
			.flatMap(from -> Arrays.stream(JobStatus.values()).map(to -> Arguments.of(from, to)));
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("allPairs")
	void allowsExactlyTheAgreedTransitions(JobStatus from, JobStatus to) {
		boolean expected = ALLOWED.contains(from + "->" + to);

		assertThat(from.canTransitionTo(to)).isEqualTo(expected);
	}

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = { "SUCCEEDED", "FAILED", "CANCELLED" })
	void terminalStates(JobStatus status) {
		assertThat(status.isTerminal()).isTrue();
	}

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = { "PENDING", "RUNNING" })
	void nonTerminalStates(JobStatus status) {
		assertThat(status.isTerminal()).isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = { "SUCCEEDED", "CANCELLED" })
	void finalStatesHaveNoWayOut(JobStatus status) {
		assertThat(JobStatus.values()).noneMatch(status::canTransitionTo);
	}

	@ParameterizedTest
	@EnumSource(JobStatus.class)
	void everyStateIsCoveredByTheAgreedLifecycle(JobStatus status) {
		// Fails if a state is added without updating the lifecycle above.
		assertThat(ALLOWED).anyMatch(t -> t.startsWith(status + "->") || t.endsWith("->" + status));
	}

}
