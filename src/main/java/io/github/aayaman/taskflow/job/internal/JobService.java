package io.github.aayaman.taskflow.job.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.github.aayaman.taskflow.job.Job;
import tools.jackson.databind.JsonNode;

@Service
public class JobService {

	private final JobRepository jobs;

	JobService(JobRepository jobs) {
		this.jobs = jobs;
	}

	/**
	 * Accepts a validated job. It is persisted as PENDING and eligible to run immediately.
	 */
	public Job submit(String type, JsonNode payload) {
		return jobs.insert(type, payload);
	}

	public Optional<Job> find(UUID id) {
		return jobs.findById(id);
	}

}
