/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.api.client.util.DateTime;
import com.google.api.gax.core.CredentialsProvider;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload.JsonPayload;
import com.google.cloud.spring.core.GcpProjectIdProvider;
import com.google.devtools.cloudtrace.v1.GetTraceRequest;
import com.google.devtools.cloudtrace.v1.Trace;
import com.google.devtools.cloudtrace.v1.TraceServiceGrpc;
import com.google.devtools.cloudtrace.v1.TraceServiceGrpc.TraceServiceBlockingStub;
import com.google.devtools.cloudtrace.v1.TraceSpan;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;

/**
 * Verifies that the logged Traces on the sample application appear in StackDriver.
 *
 * @author Daniel Zou
 * @author Mike Eltsufin
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = { Application.class })
public class TraceSampleApplicationIntegrationTests {

	private final Logger log = LoggerFactory.getLogger(this.getClass());

	@LocalServerPort
	private int port;

	@Autowired
	private GcpProjectIdProvider projectIdProvider;

	@Autowired
	private CredentialsProvider credentialsProvider;

	private String url;

	private TestRestTemplate testRestTemplate;

	private Logging logClient;

	private TraceServiceBlockingStub traceServiceStub;

	@BeforeClass
	public static void checkToRun() {
		assumeThat(
				"Google Cloud Trace integration tests are disabled. "
						+ "Please use '-Dit.trace=true' to enable them. ",
				System.getProperty("it.trace"), is("true"));
	}

	@Before
	public void setupTraceClient() throws IOException {
		this.url = String.format("http://localhost:%d/", this.port);

		// Create a new RestTemplate here because the auto-wired instance has built-in instrumentation
		// which interferes with us setting the 'x-cloud-trace-context' header.
		this.testRestTemplate = new TestRestTemplate();

		this.logClient = LoggingOptions.newBuilder()
				.setProjectId(this.projectIdProvider.getProjectId())
				.setCredentials(this.credentialsProvider.getCredentials())
				.build()
				.getService();

		ManagedChannel channel = ManagedChannelBuilder
				.forTarget("dns:///cloudtrace.googleapis.com")
				.build();

		this.traceServiceStub = TraceServiceGrpc.newBlockingStub(channel)
				.withCallCredentials(MoreCallCredentials.from(this.credentialsProvider.getCredentials()));
	}

	@Test
	public void testTracesAreLoggedCorrectly() {
		DateTime startDateTime = new DateTime(System.currentTimeMillis() - 60000); // Time is hard.

		HttpHeaders headers = new HttpHeaders();

		String uuidString = UUID.randomUUID().toString().replaceAll("-", "");

		headers.add("x-cloud-trace-context", uuidString);
		this.testRestTemplate.exchange(this.url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

		GetTraceRequest getTraceRequest = GetTraceRequest.newBuilder()
				.setProjectId(this.projectIdProvider.getProjectId())
				.setTraceId(uuidString)
				.build();

		String logFilter = String.format(
				"trace=projects/%s/traces/%s AND logName=projects/%s/logs/spring.log AND timestamp>=\"%s\"",
				this.projectIdProvider.getProjectId(), uuidString,
				this.projectIdProvider.getProjectId(), startDateTime.toStringRfc3339());

		await().atMost(4, TimeUnit.MINUTES)
				.pollInterval(Duration.ofSeconds(2))
				.ignoreExceptionsMatching(e -> e.getMessage().contains("Requested entity was not found"))
				.untilAsserted(() -> {

			log.debug("Getting trace...");
			Trace trace = this.traceServiceStub.getTrace(getTraceRequest);
			log.info("Found trace! " + trace.getTraceId()
					+ " with " + trace.getSpansCount() + " spans ("
					+ trace.getSpansList().stream().map(TraceSpan::getName).collect(Collectors.toList())
					+ ").");

			assertThat(trace.getTraceId()).isEqualTo(uuidString);
			/* The 25 expected spans are:
			 *   get /, visit-meet-endpoint, get, get /meet, get, get /meet, get, get /meet,
			 *   send-message-spring-integration, send, handle, handle, publish, send-message-pub-sub-template, publish,
			 *   next-message, on-message, next-message, on-message, send, send, handle, handle, handle, handle
			 *
			 * Example of a bad run (notice that the last line only has one "send". Two are expected!).
			 * 	Found trace! 148bcda619bd448ea6a718ca0f662bd2 with 24 spans (
			 *  [get /, visit-meet-endpoint, get, get /meet, get, get /meet, get, get /meet,
			 * 	send-message-spring-integration, send, handle, handle, publish, send-message-pub-sub-template, publish,
			 * 	next-message, on-message, handle, handle, next-message, on-message, send, handle, handle]).
			 */
			// TODO: replace with =25 after fixing https://github.com/GoogleCloudPlatform/spring-cloud-gcp/issues/597
			assertThat(trace.getSpansCount()).isGreaterThanOrEqualTo(24);
			log.debug("Trace spans match.");

			// verify custom tags
			assertThat(trace.getSpans(0).getLabelsMap().get("environment")).isEqualTo("QA");
			assertThat(trace.getSpans(0).getLabelsMap().get("session-id")).isNotNull();
			log.debug("Trace labels match.");

			assertThat(trace.getSpansList().stream().map(TraceSpan::getName).distinct())
					.containsExactly(
							"get /",
							"visit-meet-endpoint",
							"get",
							"get /meet",
							"send-message-spring-integration",
							"send",
							"handle",
							"publish",
							"send-message-pub-sub-template",
							"next-message",
							"on-message");
			log.debug("Trace span names match.");

			List<LogEntry> logEntries = new ArrayList<>();
			log.debug("Finding logs with filter: " + logFilter);
			this.logClient.listLogEntries(Logging.EntryListOption.filter(logFilter)).iterateAll()
					.forEach(le -> {
						logEntries.add(le);
						log.debug("Found log entry: " + le.toString());

						String wantTraceRegex = "projects/" + this.projectIdProvider.getProjectId() + "/traces/([a-z0-9]){32}";
						log.debug("Want trace " + wantTraceRegex + " and got " + le.getTrace());
						assertThat(le.getTrace()).matches(wantTraceRegex);

						String wantSpanRegex = "([a-z0-9]){16}";
						log.debug("Want span " + wantSpanRegex + " and got " + le.getSpanId());
						assertThat(le.getSpanId()).matches(wantSpanRegex);

						log.debug("Log trace and span match.");
					});


			List<String> logContents = logEntries.stream()
					.map(logEntry -> (String) ((JsonPayload) logEntry.getPayload())
							.getDataAsMap().get("message"))
					.collect(Collectors.toList());

			log.info("\n========================= [START OF LOG CONTENTS] =========================\n"
					+ logContents.toString()
					+ "\n========================= [END OF LOG CONTENTS]   =========================\n");
			assertThat(logContents).contains("starting busy work");
			log.debug("Found 'starting' line");
			assertThat(logContents).contains("finished busy work");
			log.debug("Found 'finishing' line");
			assertThat(logContents).contains("Message arrived! Payload: All work is done via PubSubTemplate.");
			log.debug("Found 'PubSubTemplate' line");
			assertThat(logContents).contains("Message arrived! Payload: All work is done via SI.");
			log.debug("Found 'SI' line");
		});
	}
}
