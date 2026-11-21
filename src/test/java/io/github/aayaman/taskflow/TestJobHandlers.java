package io.github.aayaman.taskflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.aayaman.taskflow.handler.JobContext;
import io.github.aayaman.taskflow.handler.JobHandler;

/**
 * No-op handlers for the job types used in API tests, so that submissions are accepted.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJobHandlers {

	@Bean
	JobHandler sendEmailHandler() {
		return noOp("SEND_EMAIL");
	}

	@Bean
	JobHandler sendSmsHandler() {
		return noOp("SEND_SMS");
	}

	@Bean
	JobHandler generateInvoiceHandler() {
		return noOp("GENERATE_INVOICE");
	}

	@Bean
	JobHandler resizeImageHandler() {
		return noOp("RESIZE_IMAGE");
	}

	private static JobHandler noOp(String type) {
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
