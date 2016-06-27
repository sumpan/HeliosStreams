/**
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 */
package com.heliosapm.streams.metrics.processor;

import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.processor.StateStoreSupplier;

import com.heliosapm.streams.metrics.StreamedMetric;

/**
 * <p>Title: StreamedMetricProcessor</p>
 * <p>Description: </p> 
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.metrics.processor.StreamedMetricProcessor</code></p>
 */

public interface StreamedMetricProcessor extends Predicate<String, StreamedMetric>, ProcessorSupplier<String, StreamedMetric>, Processor<String, StreamedMetric> {
	/**
	 * Returns the names of the data stores used by this processor
	 * @return the names of the data stores used by this processor
	 */
	public String[] getDataStoreNames();
	
	public StateStoreSupplier[] getStateStores();
	
	/**
	 * Returns the name of the topic this processor publishes to
	 * @return the name of the topic this processor publishes to
	 */
	public String getSink();

}