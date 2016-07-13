/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.heliosapm.streams.opentsdb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.hbase.async.jsr166e.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.CachedGauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import com.heliosapm.streams.chronicle.MessageListener;
import com.heliosapm.streams.chronicle.MessageQueue;
import com.heliosapm.streams.metrics.Blacklist;
import com.heliosapm.streams.metrics.StreamedMetric;
import com.heliosapm.streams.metrics.StreamedMetricDeserializer;
import com.heliosapm.streams.metrics.StreamedMetricValue;
import com.heliosapm.streams.opentsdb.plugin.PluginMetricManager;
import com.heliosapm.utils.collections.Props;
import com.heliosapm.utils.config.ConfigurationHelper;
import com.heliosapm.utils.jmx.JMXHelper;
import com.heliosapm.utils.lang.StringHelper;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import io.netty.buffer.ByteBuf;
import net.opentsdb.core.TSDB;
import net.opentsdb.stats.StatsCollector;
import net.opentsdb.tsd.RpcPlugin;

/**
 * <p>Title: KafkaRPC</p>
 * <p>Description: Kafka based metric ingestion RPC service</p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.opentsdb.KafkaRPC</code></p>
 */

public class KafkaRPC extends RpcPlugin implements KafkaRPCMBean, Runnable, MessageListener {
	/** The TSDB instance */
	static TSDB tsdb = null;
	/** The prefix on TSDB config items marking them as applicable to this service */
	public static final String CONFIG_PREFIX = "tsd.rpc.kafka.";
	/** The prefix length */
	public static final int CONFIG_PREFIX_LEN = CONFIG_PREFIX.length();
	/** The TSDB config key for the names of topics to subscribe to */
	public static final String CONFIG_TOPICS = CONFIG_PREFIX + "tsdbtopics";
	/** The default topic name to listen on */
	public static final String[] DEFAULT_TOPIC = {"tsdb-metrics"};
	/** The TSDB config key for sync processing of add data points */
	public static final String CONFIG_SYNC_ADD = CONFIG_PREFIX + "syncadd";
	/** The default sync add */
	public static final boolean DEFAULT_SYNC_ADD = true;
	
	/** The TSDB config key for the polling timeout in ms. */
	public static final String CONFIG_POLLTIMEOUT = CONFIG_PREFIX + "polltime";
	/** The default polling timeout in ms. */
	public static final long DEFAULT_POLLTIMEOUT = 10000;
	
	/** Instance logger */
	protected final Logger log = LoggerFactory.getLogger(getClass());
	/** The kafka consumer client configuration */
	protected final Properties consumerConfig = new Properties();
	/** The kafka consumer */
	protected KafkaConsumer<String, ByteBuf> consumer = null;
	/** The topics to subscribe to */
	protected String[] topics = null;
	/** Indicates if the consumer is closed */
	protected final AtomicBoolean closed = new AtomicBoolean(false);
	/** The configured consumer pool timeout */
	protected long pollTimeout = DEFAULT_POLLTIMEOUT;
	/** The configured sync add */
	protected boolean syncAdd = DEFAULT_SYNC_ADD;
	/** The subscriber thread */
	protected Thread subThread = null;
	
	/** The chronicle message queue */
	protected MessageQueue messageQueue;
	
	/** The blacklisted metric key manager */
	protected final Blacklist blacklist = Blacklist.getInstance();
	
	/** A counter tracking the number of pending data point adds */
	protected final LongAdder pendingDataPointAdds = new LongAdder();
	
	/** The metric manager for this plugin */
	protected final PluginMetricManager metricManager = new PluginMetricManager(getClass().getSimpleName());
	
	/** A meter to track the rate of points added */
	protected final Meter pointsAddedMeter = metricManager.meter("pointsAdded");
	/** A timer to track the elapsed time per message ingested */
	protected final Timer perMessageTimer = metricManager.timer("perMessage");
	
	/** The per message timer snapshot */
	protected final CachedGauge<Snapshot> perMessageTimerSnap = new CachedGauge<Snapshot>(5, TimeUnit.SECONDS) {
		@Override
		protected Snapshot loadValue() {
			return perMessageTimer.getSnapshot();
		}
	};
	
	
	
	/**
	 * Creates a new KafkaRPC
	 */
	public KafkaRPC() {
	}
	
	

	/**
	 * {@inheritDoc}
	 * @see net.opentsdb.tsd.RpcPlugin#initialize(net.opentsdb.core.TSDB)
	 */
	@Override
	public void initialize(final TSDB tsdb) {	
		KafkaRPC.tsdb = tsdb;
		
		final Properties p = new Properties();
		p.putAll(tsdb.getConfig().getMap());
		final Properties rpcConfig = Props.extractOrEnv(CONFIG_PREFIX, p, true); 
		topics = ConfigurationHelper.getArraySystemThenEnvProperty(CONFIG_TOPICS, DEFAULT_TOPIC, rpcConfig);
		pollTimeout = ConfigurationHelper.getLongSystemThenEnvProperty(CONFIG_POLLTIMEOUT, DEFAULT_POLLTIMEOUT, rpcConfig);
		syncAdd = ConfigurationHelper.getBooleanSystemThenEnvProperty(CONFIG_SYNC_ADD, DEFAULT_SYNC_ADD, rpcConfig);
		messageQueue = MessageQueue.getInstance(getClass().getSimpleName(), this, rpcConfig);
		metricManager.addExtraTag("mode", syncAdd ? "sync" : "async");
		log.info("Kafka TSDB Metric Topics: {}", Arrays.toString(topics));
		log.info("Kafka TSDB Poll Size: {}", pollTimeout);
		consumer = new KafkaConsumer<String, StreamedMetric>(consumerConfig, new StringDeserializer(), new StreamedMetricDeserializer());
		subThread = new Thread(this, "KafkaSubscriptionThread");
		subThread.start();
		JMXHelper.registerMBean(this, OBJECT_NAME);
	}
	
	
	/**
	 * Handles the kafka consumer
	 */
	public void run() {
		log.info("Kafka Subscription Thread Started");
		try {
            consumer.subscribe(Arrays.asList(topics));
            while (!closed.get()) {
            	try {
	                final ConsumerRecords<String, ByteBuf> records;
	                try {
	                	records = consumer.poll(pollTimeout);
	                	final int recordCount = records.count();
	                	if(recordCount > 0) {
	                		for(final Iterator<ConsumerRecord<String, ByteBuf>> iter = records.iterator(); iter.hasNext();) {
	                			final ConsumerRecord<String, ByteBuf> record = iter.next();
	                			messageQueue.writeEntry(record.value());
	                		}
	                	}
	                	consumer.commitAsync();
	                } catch (Exception ex) {
	                	continue;  // FIXME: increment deser errors
	                }
            	} catch (Exception ex) {
            		
            	}
            }
		} catch (Exception ex) {
			
		}
	}
	
	
	
//	im = record.value();
//	if(!im.isValued()) continue; // increment non-value
//	imv = (StreamedMetricValue)im;
//	if(blacklist.isBlackListed(im.metricKey())) continue;
//	Deferred<Object> thisDeferred = null;
//	if(imv.isDoubleValue()) {
//		thisDeferred = tsdb.addPoint(im.getMetricName(), im.getTimestamp(), imv.getDoubleValue(), im.getTags());
//	} else {
//		thisDeferred = tsdb.addPoint(im.getMetricName(), im.getTimestamp(), imv.getLongValue(), im.getTags());
//	}
//	addPointDeferreds.add(thisDeferred);  // keep all the deferreds so we can wait on them
//} catch (Exception adpe) {
//	if(im!=null) {
//		log.error("Failed to add data point for invalid metric name: {}, cause: {}", im.metricKey(), adpe.getMessage());
//		blacklist.blackList(im.metricKey());
//
//	                	try {
//		                	final ConsumerRecord<String, StreamedMetric> record = iter.next();
//		                	
//	                		} else {
//	                			log.error("Failed to add data point", adpe);
//	                		}
//	                	}
//	                }
//	                
//                	 Deferred<ArrayList<Object>> d = Deferred.group(addPointDeferreds);
//                	 d.addCallback(new Callback<Void, ArrayList<Object>>() {
//							@Override
//							public Void call(final ArrayList<Object> arg) throws Exception {
//								if(syncAdd) consumer.commitSync();
//								pendingDataPointAdds.add(-1 * recordCount);
//								final long elapsed = System.nanoTime() - startTimeNanos; 
//								perMessageTimer.update(nanosPerMessage(elapsed, recordCount), TimeUnit.NANOSECONDS);
//								pointsAddedMeter.mark(recordCount);
//								if(syncAdd) log.info("Sync Processed {} records in {} ms. Pending: {}", recordCount, TimeUnit.NANOSECONDS.toMillis(elapsed), pendingDataPointAdds.longValue());							
//								return null;
//							}                		 
//	                	 });
//                	 if(syncAdd) {
//                		 try {
//                			 d.joinUninterruptibly(30000);  // FIXME:  make configurable
//                		 } catch (Exception ex) {
//                			 log.warn("Datapoints Write Timed Out");  // FIXME: increment timeout err
//                		 }
//                	 } else {
// 	                	consumer.commitSync();
//						final long elapsed = System.nanoTime() - startTimeNanos; 
//						perMessageTimer.update(nanosPerMessage(elapsed, recordCount), TimeUnit.NANOSECONDS);
//						pointsAddedMeter.mark(recordCount);
//						log.info("Async Processed {} records in {} ms. Pending: {}", recordCount, TimeUnit.NANOSECONDS.toMillis(elapsed), pendingDataPointAdds.longValue());							
//                	 }
//            	} catch (Exception exx) {
//            		log.error("Unexpected exception in processing loop", exx);
//            	}
//            }  // end of while loop
//        } catch (WakeupException e) {
//            // Ignore exception if closing
//            if (!closed.get()) throw e;
//        } catch (Exception ex) {
//        	log.error("Failed to start subscription", ex);
//        } finally {
//            try { consumer.close(); } catch (Exception x) {/* No Op */}
//            subThread = null;
//        }		
//	}
	
	/**
	 * Computes the nano time per message
	 * @param nanosElapsed The nanos elapsed for the whole batch
	 * @param messageCount The number of messages in the batch
	 * @return the nanos per message
	 */
	protected static long nanosPerMessage(final double nanosElapsed, final double messageCount) {
		if(nanosElapsed==0 || messageCount==0) return 0;
		return (long)(nanosElapsed/messageCount);
	}

	/**
	 * {@inheritDoc}
	 * @see net.opentsdb.tsd.RpcPlugin#shutdown()
	 */
	@Override
	public Deferred<Object> shutdown() {
		final Deferred<Object> d = new Deferred<Object>();
		if(subThread!=null) {
			final Thread t = new Thread(getClass().getSimpleName() + "ShutdownThread") {
				public void run() {
					try {
						subThread.join();
						try { 
							consumer.close();
							log.info("Kafka Consumer Closed");
						} catch (Exception x) {/* No Op */}
						log.info("KafkaRPC Shutdown Cleanly");
					} catch (Exception ex) {
						log.error("KafkaRPC Dirty Shutdown:" + ex);
					} finally {
						d.callback(null);
					}
				}
			};
			t.setDaemon(true);
			t.start();
		} else {
			log.warn("KafkaRPC Sub Thread Not Running on Shutdown");
			d.callback(null);
		}
	     closed.set(true);
	     consumer.wakeup();		
		return d;
	}

	/**
	 * {@inheritDoc}
	 * @see net.opentsdb.tsd.RpcPlugin#version()
	 */
	@Override
	public String version() {
		return "2.1";
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#isSyncAdd()
	 */
	@Override
	public boolean isSyncAdd() {
		return syncAdd;
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getTotalDataPoints()
	 */
	@Override
	public long getTotalDataPoints() {
		return perMessageTimer.getCount();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getDataPointsMeanRate()
	 */
	@Override
	public double getDataPointsMeanRate() {
		return perMessageTimer.getMeanRate();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getDataPoints15mRate()
	 */
	@Override
	public double getDataPoints15mRate() {
		return perMessageTimer.getFifteenMinuteRate();
	}

	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getDataPoints5mRate()
	 */
	@Override
	public double getDataPoints5mRate() {
		return perMessageTimer.getFiveMinuteRate();
	}

	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getDataPoints1mRate()
	 */
	@Override
	public double getDataPoints1mRate() {
		return perMessageTimer.getOneMinuteRate();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getPerDataPointMeanTimeMs()
	 */
	@Override
	public double getPerDataPointMeanTimeMs() {
		return perMessageTimerSnap.getValue().getMean();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getPerDataPointMedianTimeMs()
	 */
	@Override
	public double getPerDataPointMedianTimeMs() {
		return perMessageTimerSnap.getValue().getMedian();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getPerDataPoint999pctTimeMs()
	 */
	@Override
	public double getPerDataPoint999pctTimeMs() {
		return perMessageTimerSnap.getValue().get999thPercentile();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getPerDataPoint99pctTimeMs()
	 */
	@Override
	public double getPerDataPoint99pctTimeMs() {
		return perMessageTimerSnap.getValue().get99thPercentile();
	}

	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getPerDataPoint75pctTimeMs()
	 */
	@Override
	public double getPerDataPoint75pctTimeMs() {
		return perMessageTimerSnap.getValue().get75thPercentile();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getBatchMeanRate()
	 */
	@Override
	public double getBatchMeanRate() {
		return pointsAddedMeter.getMeanRate();
	}
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getBatch15mRate()
	 */
	@Override
	public double getBatch15mRate() {
		return pointsAddedMeter.getFifteenMinuteRate();
	}

	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getBatch5mRate()
	 */
	@Override
	public double getBatch5mRate() {
		return pointsAddedMeter.getFiveMinuteRate();
	}

	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getBatch1mRate()
	 */
	@Override
	public double getBatch1mRate() {
		return pointsAddedMeter.getOneMinuteRate();
	}
	
	
	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.opentsdb.KafkaRPCMBean#getPendingDataPointAdds()
	 */
	@Override
	public long getPendingDataPointAdds() {
		return pendingDataPointAdds.longValue();
	}
	

	/**
	 * {@inheritDoc}
	 * @see net.opentsdb.tsd.RpcPlugin#collectStats(net.opentsdb.stats.StatsCollector)
	 */
	@Override
	public void collectStats(final StatsCollector collector) {
		metricManager.collectStats(collector);
	}



	/**
	 * {@inheritDoc}
	 * @see com.heliosapm.streams.chronicle.MessageListener#onMetric(io.netty.buffer.ByteBuf)
	 */
	@Override
	public void onMetric(ByteBuf buf) {
		// TODO Auto-generated method stub
		
	}

}
