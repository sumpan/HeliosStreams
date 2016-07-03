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
package com.heliosapm.streams.admin;

import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.CuratorZookeeperClient;
import org.apache.curator.RetryPolicy;
import org.apache.curator.RetrySleeper;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.listen.Listenable;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.cache.TreeCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.zookeeper.ClientCnxn;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import com.heliosapm.utils.jmx.JMXManagedThreadFactory;

/**
 * <p>Title: AdminFinder</p>
 * <p>Description: Zookeeper client to find and listen on the StreamHubAdmin server.</p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.admin.AdminFinder</code></p>
AdminFinder:
============
start connect loop
on connect
	create treecache (FILTER)
	if url not present, listen on url
	on url drop latch

listen on node removed/changed
	if removed, start listener
	if changed, update and reset app

 */

public class AdminFinder implements Watcher, RetryPolicy {
	/** The singleton instance */
	private static volatile AdminFinder instance = null;
	/** The singleton instance ctor lock */
	private static final Object lock = new Object();
	/** The zookeep parent node name to retrieve the streamhub admin url */
	public static final String ZOOKEEP_URL_ROOT = "/streamhub/admin";
	/** The zookeep node name to retrieve the streamhub admin url */
	public static final String ZOOKEEP_URL = ZOOKEEP_URL_ROOT + "/url";
	/** The command line arg prefix for the zookeep connect */
	public static final String ZOOKEEP_CONNECT_ARG = "--zookeep=";
	/** The default zookeep connect */
	public static final String DEFAULT_ZOOKEEP_CONNECT = "localhost:2181";
	/** The command line arg prefix for the zookeep session timeout in ms. */
	public static final String ZOOKEEP_TIMEOUT_ARG = "--ztimeout=";
	/** The default zookeep session timeout in ms. */
	public static final int DEFAULT_ZOOKEEP_TIMEOUT = 15000;
	/** The command line arg prefix for the retry pause period in ms. */
	public static final String RETRY_ARG = "--retry=";
	/** The default retry pause period in ms. */
	public static final int DEFAULT_RETRY = 15000;
	/** The command line arg prefix for the connect retry timeout in ms. */
	public static final String CONNECT_TIMEOUT_ARG = "--ctimeout=";
	/** The default zookeep connect timeout in ms. */
	public static final int DEFAULT_CONNECT_TIMEOUT = 5000;
	/** The UTF character set */
	public static final Charset UTF8 = Charset.forName("UTF8");
	
	/**
	 * Initializes and acquires the AdminFinder singleton instance
	 * @param args The app command line arguments
	 * @return the AdminFinder singleton instance
	 */
	public static AdminFinder getInstance(final String[] args) {
		if(instance==null) {
			synchronized(lock) {
				if(instance==null) {
					instance = new AdminFinder(args);
				}
			}
		}
		return instance;
	}
	
	/**
	 * Acquires the initialized AdminFinder singleton instance
	 * @return the AdminFinder singleton instance
	 */
	public static AdminFinder getInstance() {
		if(instance==null) {
			synchronized(lock) {
				if(instance==null) {
					throw new IllegalStateException("The AdminFinder has not been initialized");
				}
			}
		}
		return instance;
	}
	
	
	/** Instance logger */
	protected final Logger log = LogManager.getLogger(AdminFinder.class);
	/** The zookeep connect string */
	protected String zookeepConnect = null;
	/** The zookeep session timeout in ms. */
	protected int zookeepTimeout = -1;
	/** The curator zookeep client */
	protected CuratorZookeeperClient czk = null;
	/** The zookeep client */
	protected ZooKeeper zk = null;
	
	/** The tree cache instance */
	protected TreeCache treeCache = null;
	
	/** The curator framework */
	protected CuratorFramework cf = null;
	
	/** Indicates if we're connected */
	protected final AtomicBoolean connected = new AtomicBoolean(false);
	/** The admin URL */
	protected final AtomicReference<String> adminURL = new AtomicReference<String>(null);
	
	/** The zookeep session id */
	protected Long sessionId = null;
	/** The initial connect retry timeout in ms. */
	protected int connectTimeout = -1;
	/** The reconnect payse time in ms. */
	protected int retryPauseTime = -1;
	/** The thread factry for the curator client */
	protected final ThreadFactory threadFactory = JMXManagedThreadFactory.newThreadFactory("ZooKeeperAdminFinder", true); 
	

	/**
	 * Creates a new AdminFinder
	 * @param args the command line args
	 */
	public AdminFinder(final String[] args) {
		zookeepConnect = findArg(ZOOKEEP_CONNECT_ARG, DEFAULT_ZOOKEEP_CONNECT, args);
		zookeepTimeout = findArg(ZOOKEEP_TIMEOUT_ARG, DEFAULT_ZOOKEEP_TIMEOUT, args);
		connectTimeout = findArg(CONNECT_TIMEOUT_ARG, DEFAULT_CONNECT_TIMEOUT, args);
		retryPauseTime = findArg(RETRY_ARG, DEFAULT_RETRY, args);
		cf = CuratorFrameworkFactory.builder()
				.canBeReadOnly(false)
				.connectionTimeoutMs(connectTimeout)
				.sessionTimeoutMs(zookeepTimeout)
				.connectString(zookeepConnect)
				.retryPolicy(new ExponentialBackoffRetry(5000, 200))
				//.retryPolicy(this)
				.threadFactory(threadFactory)
				.build();		
	}
	
	/*
	 * States:
	 * =======
	 * cf not started
	 * cf started / not connected
	 * cf started / connected
	 * 
	 */
	
	protected void start() {
		if(!connected.get()) {
			log.info("Starting Connection Loop");
			final CountDownLatch connectLatch = new CountDownLatch(1);
			threadFactory.newThread(new Runnable(){
				public void run() {
					if(cf.getState()==CuratorFrameworkState.STOPPED) {
						loff();
						try {
							cf.start();
							try {
								cf.blockUntilConnected();
								
							} catch (InterruptedException iex) {
								log.error("Interupted while waiting on connect", iex);
							}
						} finally {
							lon();
						}
					}
					
				}
			}).start();
		}
	}
	
	public void startx() {
		try {
			log.info("Starting Connection Loop");
//			loff();
			//CuratorFramework cf = CuratorFrameworkFactory.newClient(zookeepConnect, zookeepTimeout, connectTimeout, this);
					
			CuratorFramework cf = CuratorFrameworkFactory.builder()
					.canBeReadOnly(false)
					.connectionTimeoutMs(connectTimeout)
					.sessionTimeoutMs(zookeepTimeout)
					.connectString(zookeepConnect)
					
					.retryPolicy(new ExponentialBackoffRetry(5000, 200))
					//.retryPolicy(this)
					.threadFactory(threadFactory)
					.build();
			cf.start();
			log.info("CF Started");
			cf.blockUntilConnected();
			lon();
			log.info("Connected");
			czk = cf.getZookeeperClient();
			log.info("CZK Connected: {}",  czk.isConnected());
			zk = czk.getZooKeeper();
			Stat stat = zk.exists(ZOOKEEP_URL_ROOT, false);
			if(stat==null) {
				
				log.error("Connected ZooKeeper server does not contain the StreamHubAdmin node. Are you connected to the right server ?");
				TreeCache tc = new TreeCache(cf, "/");
				tc.start();
				log.info("TreeCache Started");
				Listenable<TreeCacheListener> listen = tc.getListenable();
				listen.addListener(new TreeCacheListener(){
					/**
					 * {@inheritDoc}
					 * @see org.apache.curator.framework.recipes.cache.TreeCacheListener#childEvent(org.apache.curator.framework.CuratorFramework, org.apache.curator.framework.recipes.cache.TreeCacheEvent)
					 */
					@Override
					public void childEvent(CuratorFramework client, TreeCacheEvent event) throws Exception {
						ChildData cd = event.getData();
						if(cd!=null) {
							log.info("TreeCache Bound [{}]", cd.getPath());
							// /streamhub/admin
							// /streamhub/admin/url
						}
					}
				});
				Thread.currentThread().join();
				//System.exit(-1);
			} else {
				byte[] data = zk.getData(ZOOKEEP_URL, false, stat);
				String urlStr = new String(data, UTF8);
				System.out.println("StreamHubAdmin is at: [" + urlStr + "]");
			}
			
		} catch (Exception ex) {
			throw new RuntimeException("Failed to acquire zookeeper connection at [" + zookeepConnect + "]", ex);
		}
	}
	
	public static void main(final String[] args) {
		AdminFinder af = new AdminFinder(args);
		try {
			af.start();
		} catch (Exception ex) {
			ex.printStackTrace(System.err);
		}
	}
	
	protected volatile Level cxnLevel = Level.INFO;
	
	protected void loff() {
		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration config = ctx.getConfiguration();
		LoggerConfig loggerConfig = config.getLoggerConfig(ClientCnxn.class.getName());
		cxnLevel = loggerConfig.getLevel();
		log.info("CXN Logger Level: {}", cxnLevel.name());
		loggerConfig.setLevel(Level.ERROR);
		ctx.updateLoggers();		
	}
	
	protected void lon() {
		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration config = ctx.getConfiguration();
		LoggerConfig loggerConfig = config.getLoggerConfig(ClientCnxn.class.getName()); 
		loggerConfig.setLevel(cxnLevel);
		ctx.updateLoggers();		
	}
	
	
	/**
	 * Finds a command line arg value
	 * @param prefix The prefix
	 * @param defaultValue The default value if not found
	 * @param args The command line args to search
	 * @return the value
	 */
	private static int findArg(final String prefix, final int defaultValue, final String[] args) {
		final String s = findArg(prefix, (String)null, args);
		if(s==null) return defaultValue;
		try {
			return Integer.parseInt(s);
		} catch (Exception ex) {
			return defaultValue;
		}
	}
	
	/**
	 * Finds a command line arg value
	 * @param prefix The prefix
	 * @param defaultValue The default value if not found
	 * @param args The command line args to search
	 * @return the value
	 */
	private static long findArg(final String prefix, final long defaultValue, final String[] args) {
		final String s = findArg(prefix, (String)null, args);
		if(s==null) return defaultValue;
		try {
			return Long.parseLong(s);
		} catch (Exception ex) {
			return defaultValue;
		}
	}
	
	
	
	/**
	 * Finds a command line arg value
	 * @param prefix The prefix
	 * @param defaultValue The default value if not found
	 * @param args The command line args to search
	 * @return the value
	 */
	private static String findArg(final String prefix, final String defaultValue, final String[] args) {
		for(String s: args) {
			if(s.startsWith(prefix)) {
				s = s.replace(prefix, "").trim();
				return s;
			}
		}
		return defaultValue;
	}

	/**
	 * {@inheritDoc}
	 * @see org.apache.zookeeper.Watcher#process(org.apache.zookeeper.WatchedEvent)
	 */
	@Override
	public void process(final WatchedEvent event) {			
		switch(event.getState()) {
			case Disconnected:
				connected.set(false);
				log.info("ZooKeep Session Disconnected");	
				sessionId = null;
				break;
			case Expired:
				connected.set(false);
				log.info("ZooKeep Session Expired");
				sessionId = null;
				break;
			case SyncConnected:
				connected.set(true);
				sessionId = getSessionId();
				log.info("ZooKeep Connected. SessionID: [{}]", sessionId);
				break;
			default:
				log.info("ZooKeep Event: [{}]", event);
				break;		
		}		
	}
	
	
	/**
	 * Acquires the curator client's zookeeper client's session id
	 * @return the curator client's zookeeper client's session id
	 */
	protected Long getSessionId() {
		if(czk==null) return null;
		try {
			return czk.getZooKeeper().getSessionId();
		} catch (Exception ex) {
			log.error("Disaster. Curator client does not have a zookeeper. Developer Error", ex);
			System.exit(-1);
			return null;
		}
	}

	private final AtomicInteger retries = new AtomicInteger(0); 
	
	/**
	 * {@inheritDoc}
	 * @see org.apache.curator.RetryPolicy#allowRetry(int, long, org.apache.curator.RetrySleeper)
	 */
	@Override
	public boolean allowRetry(final int retryCount, final long elapsedTimeMs, final RetrySleeper sleeper) {
		final int r = retries.incrementAndGet();
		if(elapsedTimeMs < retryPauseTime) {
			try {
				sleeper.sleepFor(retryPauseTime-elapsedTimeMs, TimeUnit.MILLISECONDS);
			} catch (Exception ex) {
				log.warn("RetrySleeper Interrupted", ex);
			}
		}
		log.info("Attempting connect retry #{} to [{}]", r, zookeepConnect);
		return true;
	}

	

}
