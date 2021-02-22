/*
 * Copyright 2019-2020 the original author or authors.
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

package com.google.cloud.spring.autoconfigure.pubsub.health;

import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.verify;

/**
 * Tests for the PubSub Health Indicator.
 *
 * @author Patrik Hörlin
 */
@RunWith(MockitoJUnitRunner.class)
public class PubSubHealthTemplateTest {

	@Mock
	private PubSubTemplate pubSubTemplate;

	@Test
	public void testPullAsync() {
		PubSubHealthTemplate healthTemplate = new PubSubHealthTemplate(pubSubTemplate, "test-subscription", 1000);
		healthTemplate.pullAsync();
		verify(pubSubTemplate).pullAsync("test-subscription", 1, true);
	}
}