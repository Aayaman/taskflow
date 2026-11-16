package io.github.aayaman.taskflow.job;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.github.aayaman.taskflow.job.internal.JobService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/jobs")
class JobController {

	private final JobService jobService;

	JobController(JobService jobService) {
		this.jobService = jobService;
	}

	@PostMapping
	ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request) {
		Job job = jobService.submit(request.type(), request.payload());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(job.id())
			.toUri();
		return ResponseEntity.created(location).body(JobResponse.from(job));
	}

}
