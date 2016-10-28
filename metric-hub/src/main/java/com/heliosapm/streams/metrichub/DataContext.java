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
package com.heliosapm.streams.metrichub;

import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;

/**
 * <p>Title: DataContext</p>
 * <p>Description: Accepts a {@link MetricsMetaAPI} expression and a number of data query parameters to build an OpenTSDB <b>/api/query</b>
 * JSON request, and executes it against the provided server.</p> 
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.metrichub.DataQuery</code></p>
 */

public class DataContext {
	/** The start time in ms */
	protected long startTimeMs = -1L;
	/** The start time as an expression */
	protected String startTime = null;
	/** The end time in ms */
	protected long endTimeMs = -1L;
	/** The end time as an expression */
	protected String endTime = null;
	/** The aggregator to use */
	protected Aggregator aggregator = null;
	
	/** The rate options */
	protected RateOptions rateOptions = null;

	
	/** The downsampling expression */
	protected String downSampling = null;

	
	/** Whether or not to output data point timestamps in milliseconds (true) or seconds (false). */
	protected boolean msResolution = false;
	/** Whether or not to include annotations (true) or not (false). */
	protected boolean includeAnnotations = false;
	/** Whether or not to include global annotations (true) or not (false). */
	protected boolean includeGlobalAnnotations = false;
	/** Whether or not to TSUIDs in the response (true) or not (false). */
	protected boolean includeTsuids = false;
	/** Whether or not to show a performance summary in the response (true) or not (false). */
	protected boolean showSummary = false;
	/** Whether or not to show a the submitted query in the response (true) or not (false). */
	protected boolean showQuery = false;
	
	
	/** Indicates if the call to OpenTSDB is for a deletion. <b>BE CAREFUL !</b> */
	protected boolean deletion = false;
	
	
	/** The standard TS format */
	public static final String DEFAULT_TS_FORMAT = "yyyy/MM/dd-HH:mm:ss";  // eg. 2016/10/27-00:00:00
	
	/** The simple date formatter for the standard format. Be sure to use via synchronized method */
	private static final SimpleDateFormat DEFAULT_TS_SDF = new SimpleDateFormat(DEFAULT_TS_FORMAT);
	
	
	static class RateOptions {
		/** Whether or not the underlying data is a monotonically increasing counter that may roll over */
		boolean monotonic = false;
		/** A positive number representing the maximum value for the counter. */
		long counterMax = Long.MAX_VALUE;
		/** An optional value that, when exceeded, will cause the aggregator to return a 0 instead of the calculated rate. */
		long resetValue = 0L;
		
		/**
		 * Renders the options for query building. e.g. <b><code>:rate{counter,,1000}</code></b>
		 * {@inheritDoc}
		 * @see java.lang.Object#toString()
		 */
		public String toString() {			
			final StringBuilder b = new StringBuilder(":rate{");
			if(monotonic) b.append("counter,").append(counterMax).append(",");
			return b.append(resetValue).append("}").toString();
		}
	}
	
	
	/**
	 * Creates a new DataContext
	 * @param startTime The start time in seconds or millis
	 * @param aggregator The aggregator to apply
	 */
	public DataContext(final long startTime, final Aggregator aggregator) {
		if(aggregator==null) throw new IllegalArgumentException("The passed aggregator was null");
		this.aggregator = aggregator;
		this.startTimeMs = toMsTime(startTime);
	}
	
	/**
	 * Creates a new DataContext
	 * @param startTime The start time in the standard format ({@link #DEFAULT_TS_FORMAT} or an "ago" expression, e.g. <b>4h-ago</b>
	 * @param aggregator The aggregator to apply
	 */
	public DataContext(final String startTime, final Aggregator aggregator) {
		if(startTime==null || startTime.trim().isEmpty()) throw new IllegalArgumentException("The passed startTime was null or empty");
		if(aggregator==null) throw new IllegalArgumentException("The passed aggregator was null");		
		this.aggregator = aggregator;
		if(startTime.toLowerCase().contains("-ago")) {
			this.startTime = startTime.trim();
			startTimeMs = -1L;
		} else {		
			startTimeMs = standardFormatToMs(startTime);
		}
	}
	
	/**
	 * Creates a new DataContext
	 * @param startTime The start time in a non-standard format 
	 * @param format The simple date format of the supplied start time
	 * @param aggregator The aggregator to apply
	 */
	public DataContext(final String startTime, final String format, final Aggregator aggregator) {
		if(aggregator==null) throw new IllegalArgumentException("The passed aggregator was null");
		if(startTime==null || startTime.trim().isEmpty()) throw new IllegalArgumentException("The passed startTime was null or empty");
		if(format==null || format.trim().isEmpty()) throw new IllegalArgumentException("The passed time format was null or empty");
		this.aggregator = aggregator;
		final SimpleDateFormat sdf = new SimpleDateFormat(format.trim());
		try {
			startTimeMs = sdf.parse(startTime.trim()).getTime();
		} catch (Exception ex) {
			throw new IllegalArgumentException("Failed to convert time [" + startTime + "] to supplied format [" + format + "]", ex);
		}
	}
	

	/**
	 * Enables rate reporting and sets the reset value
	 * @param resetValue The value at which the aggregator returns zero instead of the calculated rate
	 * @return this data context
	 */
	public DataContext rateResetValue(final long resetValue) {
		if(rateOptions==null) rateOptions = new RateOptions();
		rateOptions.resetValue = resetValue;
		return this;
	}
	
	
	/**
	 * Enables rate reporting and sets the rate options maximum value for the counter and enables monotonic
	 * @param counterMax A positive number representing the maximum value for the counter
	 * @return this data context
	 */
	public DataContext rateCounterMax(final long counterMax) {
		if(counterMax < 1) throw new IllegalArgumentException("Countermax must be positive");
		if(rateOptions==null) rateOptions = new RateOptions();
		rateOptions.counterMax = counterMax;
		rateOptions.monotonic = true;
		return this;
	}
	
	/**
	 * Disables rate reporting
	 * @return this data context
	 */
	public DataContext unrate() {
		rateOptions = null;
		return this;
	}

	/**
	 * Enables rate reporting and sets the rate options monotonic counter to true
	 * @return this data context
	 */
	public DataContext rateMonotonic() {
		if(rateOptions==null) rateOptions = new RateOptions();
		rateOptions.monotonic = true;
		return this;
	}
	
	/**
	 * Formats a string in the default timestamp format to a long utc
	 * @param time The time string to format
	 * @return the supplied time in long utc
	 */
	public static long standardFormatToMs(final String time) {
		if(time==null || time.trim().isEmpty()) throw new IllegalArgumentException("The passed time was null or empty");
		try {
			synchronized(DEFAULT_TS_SDF) {
				return DEFAULT_TS_SDF.parse(time.trim()).getTime();
			}
		} catch (Exception ex) {
			throw new IllegalArgumentException("Failed to convert time [" + time + "] to standard format [" + DEFAULT_TS_FORMAT + "]", ex);
		}
	}
	
	/**
	 * Converts the passed string to a ms timestamp.
	 * If the parsed long has less than 13 digits, it is assumed to be in seconds.
	 * Otherwise assumed to be in milliseconds.
	 * @param value The string value to parse
	 * @return a ms timestamp
	 */
	public static long toMsTime(final String value) {
		final long v = (long)Double.parseDouble(value.trim());
		return digits(v) < 13 ? TimeUnit.SECONDS.toMillis(v) : v; 
	}
	
	/**
	 * Examines the passed timestamp and if determined to be in seconds, converts to millis
	 * @param v The time to test and convert
	 * @return the passed time in ms.
	 */
	public static long toMsTime(final long v) {
		return digits(v) < 13 ? TimeUnit.SECONDS.toMillis(v) : v;		
	}
	
	/**
	 * Determines the number of digits in the passed long
	 * @param v The long to test
	 * @return the number of digits
	 */
	public static int digits(final long v) {
		if(v==0) return 1;
		return (int)(Math.log10(v)+1);
	}

	/**
	 * Returns the start time as a ms timestamp, or -1 if an expression is being used 
	 * @return the startTimeMs  the start time as a ms timestamp, or -1 if an expression is being used
	 */
	public long getStartTimeMs() {
		return startTimeMs;
	}

	/**
	 * Sets the start time as a ms or s timestamp
	 * @param startTime the start time
	 * @return this data context
	 */
	public DataContext start(final long startTime) {
		this.startTimeMs = toMsTime(startTimeMs);
		this.startTime = null;
		return this;
	}

	/**
	 * Returns the start time expression or null if one is not set
	 * @return the startTime expression or null if one is not set
	 */
	public String getStartTime() {
		return startTime;
	}

	/**
	 * Sets the start time expression
	 * @param startTime the startTime to set
	 * @return this data context
	 */
	public DataContext startTime(final String startTime) {
		Interval.toMsTime(startTime); // to validate
		this.startTime = startTime;
		startTimeMs = -1L;
		return this;
	}

	/**
	 * Returns the end time expression or null if one is not set
	 * @return the end time expression or null if one is not set
	 */
	public String getEndTime() {
		return endTime;
	}

	/**
	 * Sets the end time expression
	 * @param endTime the endTime to set
	 * @return this data context
	 */
	public DataContext endTime(final String endTime) {
		Interval.toMsTime(endTime); // to validate
		this.endTime = endTime;
		endTimeMs = -1L;
		return this;
	}

	/**
	 * Returns the aggregator
	 * @return the aggregator
	 */
	public Aggregator getAggregator() {
		return aggregator;
	}

	/**
	 * Sets the aggregator
	 * @param aggregator the aggregator to set
	 * @return this data context
	 */
	public DataContext aggregator(final Aggregator aggregator) {
		if(aggregator==null) throw new IllegalArgumentException("The passed aggregator was null");
		this.aggregator = aggregator;
		return this;
	}

	/**
	 * Returns the downsampling expression or null if one is not set
	 * @return the downSampling expression
	 */
	public String getDownSampling() {
		return downSampling;
	}

	/**
	 * Sets the downsampling expression
	 * @param downSampling the downSampling to set
	 * @return this data context
	 */
	public DataContext downSampling(final String downSampling) {
		if(downSampling==null || downSampling.trim().isEmpty()) throw new IllegalArgumentException("The passed downsampling expression was null or empty");
		Downsampler.validateDownsamplerExpression(downSampling);  // validates
		this.downSampling = downSampling.trim();
		return this;
	}
	
	/**
	 * Disables downsampling
	 * @return this data context
	 */
	public DataContext noDownSample() {
		this.downSampling = null;
		return this;
	}
	

	/**
	 * Indicates if the responses will be in ms. resolution
	 * @return true if millisecond resolution, false if second resolution
	 */
	public boolean isMsResolution() {
		return msResolution;
	}

	/**
	 * Specifies if the responses will be in ms. resolution
	 * @param msResolution true for millisecond resolution, false for second resolution
	 * @return this data context
	 */
	public DataContext msResolution(final boolean msResolution) {
		this.msResolution = msResolution;
		return this;
	}

	/**
	 * Indicates if TSUID related annotations should be returned
	 * @return true if TSUID related annotations should be returned, false otherwise
	 */
	public boolean isIncludeAnnotations() {
		return includeAnnotations;
	}

	/**
	 * Specifies if TSUID related annotations should be returned.
	 * <b>Not implemented yet</b>
	 * @param includeAnnotations true if TSUID related annotations should be returned, false otherwise
	 * @return this data context
	 */
	public DataContext includeAnnotations(boolean includeAnnotations) {
		this.includeAnnotations = includeAnnotations;
		return this;
	}
	
	/**
	 * Indicates if timestamp related (global) annotations should be returned
	 * @return true if timestamp related (global) annotations should be returned, false otherwise
	 */
	public boolean isIncludeGlobalAnnotations() {
		return includeGlobalAnnotations;
	}

	/**
	 * Specifies if timestamp related (global) annotations should be returned.
	 * <b>Not implemented yet</b>
	 * @param includeAnnotations true if timestamp related (global) annotations should be returned, false otherwise
	 * @return this data context
	 */
	public DataContext includeGlobalAnnotations(boolean includeAnnotations) {
		this.includeGlobalAnnotations = includeAnnotations;
		return this;
	}
	


	/**
	 * Indicates if TSUIDs should be returned in the response
	 * @return true if TSUIDs should be returned in the response, false otherwise
	 */
	public boolean isIncludeTsuids() {
		return includeTsuids;
	}

	/**
	 * Specifies if TSUIDs should be returned in the response
	 * @param includeTsuids true if TSUIDs should be returned in the response, false otherwise
	 * @return this data context
	 */
	public DataContext includeTsuids(final boolean includeTsuids) {
		this.includeTsuids = includeTsuids;
		return this;
	}

	/**
	 * Indicates if an elapsed time summary should be included in the response
	 * @return true if summary is included, false otherwise
	 */
	public boolean isShowSummary() {
		return showSummary;
	}

	/**
	 * Specifies if an elapsed time summary should be included in the response
	 * @param showSummary true to include a summary, false otherwise
	 * @return this data context
	 */
	public DataContext showSummary(final boolean showSummary) {
		this.showSummary = showSummary;
		return this;
	}

	/**
	 * Indicates if the submitted query should be included in the response
	 * @return true if the submitted query should be included in the response
	 */
	public boolean isShowQuery() {
		return showQuery;
	}

	/**
	 * Specifies if the submitted query should be included in the response
	 * @param showQuery the showQuery to set
	 * @return this data context
	 */
	public DataContext showQuery(final boolean showQuery) {
		this.showQuery = showQuery;
		return this;
	}

	/**
	 * Indicates if the submitted request should be a deletion
	 * @return true if the submitted request should be a deletion, false otherwise
	 */
	public boolean isDeletion() {
		return deletion;
	}

	/**
	 * Specifies if the submitted request should be a deletion
	 * @param deletion true if the submitted request should be a deletion, false for a query
	 * @return this data context
	 */
	public DataContext deletion(final boolean deletion) {
		this.deletion = deletion;
		return this;
	}	

}