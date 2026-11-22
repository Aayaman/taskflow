package io.github.aayaman.taskflow.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobQueueTest {

	@Test
	void keepsShortErrorsUnchanged() {
		assertThat(JobQueue.truncate("boom")).isEqualTo("boom");
		assertThat(JobQueue.truncate("e".repeat(JobQueue.MAX_ERROR_LENGTH))).hasSize(JobQueue.MAX_ERROR_LENGTH);
	}

	@Test
	void truncatesToMaxLength() {
		assertThat(JobQueue.truncate("e".repeat(JobQueue.MAX_ERROR_LENGTH + 1))).hasSize(JobQueue.MAX_ERROR_LENGTH);
	}

	@Test
	void countsAndCutsByCodePoints() {
		// U+1D11E MUSICAL SYMBOL G CLEF is one character but two UTF-16 code units.
		String clef = new String(Character.toChars(0x1D11E));
		String error = clef.repeat(JobQueue.MAX_ERROR_LENGTH + 1);

		String truncated = JobQueue.truncate(error);

		assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(JobQueue.MAX_ERROR_LENGTH);
		assertThat(truncated).isEqualTo(clef.repeat(JobQueue.MAX_ERROR_LENGTH));
	}

}
