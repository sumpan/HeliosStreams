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
package com.heliosapm.streams.metrics.processors.impl;

import com.heliosapm.streams.metrics.StreamedMetric;
import com.heliosapm.streams.metrics.processors.AbstractStreamedMetricProcessorSupplier;

/**
 * <p>Title: StraightThroughMetricProcessorSupplier</p>
 * <p>Description: Receives test metrics, converts to binary and forwards</p> 
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.metrics.processors.impl.StraightThroughMetricProcessorSupplier</code></p>
 */

public class StraightThroughMetricProcessorSupplier extends AbstractStreamedMetricProcessorSupplier<String, StreamedMetric, String, StreamedMetric> {
	/** The maximum number of metrics to forward without a commit */
	protected int maxForwards = 1000;

	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.metrics.processors.AbstractStreamedMetricProcessorSupplier#getProcessor()
	 */
	@Override
	public StraightThroughMetricProcessor getProcessor() {		
		return new StraightThroughMetricProcessor(period, maxForwards);
	}
	
	/**
	 * Returns the maximum number of metrics to forward without a commit
	 * @return the maxForwards
	 */
	public int getMaxForwards() {
		return maxForwards;
	}
	
	/**
	 * Sets the maximum number of metrics to forward without a commit
	 * @param maxForwards the maxForwards to set
	 */
	public void setMaxForwards(final int maxForwards) {
		if(maxForwards < 1) throw new IllegalArgumentException("Invalid maxForwards value:" + maxForwards);
		this.maxForwards = maxForwards;
	}

}
