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
package com.heliosapm.streams.admin.nodes;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedMetric;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedOperationParameter;
import org.springframework.jmx.export.annotation.ManagedOperationParameters;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.jmx.support.MetricType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.codahale.metrics.CachedGauge;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheStats;
import com.google.common.cache.LoadingCache;
import com.google.common.io.Files;
import com.heliosapm.utils.lang.StringHelper;
import com.heliosapm.utils.url.URLHelper;

/**
 * <p>Title: NodeConfigurationServer</p>
 * <p>Description: The endpoint that responds to worker nodes requesting marching orders</p> 
 * <p>Company: Helios Development Group LLC</p>
 * @author Whitehead (nwhitehead AT heliosdev DOT org)
 * <p><code>com.heliosapm.streams.admin.nodes.NodeConfigurationServer</code></p>
 */
@RestController
@RequestMapping(value="/nodeconfig")
@Configuration
@Component
@ManagedResource(
		objectName="com.heliosapm.streams.admin:service=NodeConfigurationServer", 
		description="The endpoint that responds to worker nodes requesting marching orders"		
)

public class NodeConfigurationServer implements InitializingBean {
	/** Instance logger */
	protected final Logger log = LogManager.getLogger(NodeConfigurationServer.class);
	
	
	
	/** The configuration directory name */
	@Value("${workers.nodes.config.dir}")
	protected String configDir = null;
	/** The content cache spec */
	@Value("${workers.nodes.config.cachespec}")
	protected String cacheSpec = null;
	
	/** The absolute directory */
	protected File dir = null;
	
	/** The content cache */
	protected LoadingCache<String, KeyedFileContent> contentCache;
	/** The cache loader */
	protected CacheLoader<String, KeyedFileContent> cacheLoader;
	
	/** Timed gauge to cache the cache stats */
	protected CachedGauge<CacheStats> cacheStats = null;
	
	/**
	 * Retrieves the configuration for the passed host and app
	 * @param host The requesting host
	 * @param appname The requested app for which configuration should be delivered
	 * @return a properties file in string format
	 */
	@RequestMapping(value="/{host}/{appname}.properties", method=RequestMethod.GET, produces={"text/x-java-properties"})
	
	public String getConfigurationProperties(@PathVariable final String host, @PathVariable final String appname) {
		final String _host = host.toLowerCase().trim().split("\\.")[0];
		final String _appname = appname.toLowerCase().trim()  + ".properties";
		final String key = _host + "/" + _appname;
		return getContent(key);
	}
	
	
	/**
	 * Returns the content for the passed key, reloading if it has expired
	 * @param key the key to get content for
	 * @return the content
	 */
	protected String getContent(final String key) {
		try {
			KeyedFileContent k = contentCache.get(key);
			if(k.isExpired()) {
				contentCache.invalidate(key);
				k = contentCache.get(key);
				log.info("Reloaded [{}]", key);
			}
			return k.getContent();
		} catch (Exception ex) {
			throw new RuntimeException("Failed to get content for [" + key + "]");
		}
	}
	
	/**
	 * Creates the cache loader
	 * @return the cache loader 
	 */
	protected CacheLoader<String, KeyedFileContent> loader() {  
		return new CacheLoader<String, KeyedFileContent>() {
			@Override
			public KeyedFileContent load(final String key) throws Exception {
				return new KeyedFileContent(getContentFileForKey(key));
			}
		};
	}
	
	/**
	 * Finds the file for the passed host/app key
	 * @param key The key to find the file for
	 * @return the file
	 */
	protected File getContentFileForKey(final String key) {
		final String[] segments = StringHelper.splitString(key, '/', true);
		final String _host = segments[0];
		final String _appname = segments[1];
		File hostDir = new File(dir, _host);
		if(!hostDir.isDirectory()) {
			hostDir = new File(dir, "default");
			if(!hostDir.isDirectory()) {
				final String msg = "Failed to find host directory for [" + _host + "] or default";
				log.error(msg);
				throw new RuntimeException(msg);
			}
		}
		File appFile = new File(hostDir, _appname);
		if(!appFile.isFile()) {
			appFile = new File(hostDir, "default.properties");
			if(!appFile.isFile()) {
				final String msg = "Failed to find app config in [" + hostDir + "] for [" + _appname + "] or default.properties";
				log.error(msg);
				throw new RuntimeException(msg);
			}
		}
		return appFile;
		
	}
	
	/**
	 * {@inheritDoc}
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 */
	@Override
	public void afterPropertiesSet() throws Exception {		
		dir = new File(configDir).getAbsoluteFile();
		if(!dir.isDirectory()) throw new IllegalArgumentException("The configuration directory [" + configDir + "] is invalid");
		log.info("Configuration Directory: [{}]", dir);
		if(!cacheSpec.contains("recordStats")) cacheSpec = cacheSpec + ",recordStats";
		cacheLoader = loader();
		contentCache = CacheBuilder.from(cacheSpec).build(cacheLoader);
		cacheStats = new CachedGauge<CacheStats>(5, TimeUnit.SECONDS) {
			@Override
			protected CacheStats loadValue() {				
				return contentCache.stats();
			}
		};
		reloadCache();
		log.info("Loaded [{}] KeyedFileContents", contentCache.size());
	}
	
	/**
	 * Reloads the cache
	 */
	protected void reloadCache() {
		for(File f : Files.fileTreeTraverser().preOrderTraversal(dir)) {
			if(!f.getName().endsWith(".properties"))  continue;
			final String key = new StringBuilder(f.getParentFile().getName()).append("/").append(f.getName()).toString();			
			contentCache.put(key, new KeyedFileContent(f));
		}		
	}
	
	
	/**
	 * Returns the average time spent loading new values.
	 * @return the average time spent loading new values.
	 */
	@ManagedMetric(category="NodeConfiguration", description="The average time spent loading new values", metricType=MetricType.GAUGE, unit="ns.")
	public double getAverageLoadPenalty() {
		return cacheStats.getValue().averageLoadPenalty();
	}
	
	/**
	 * Returns the cache hit count
	 * @return the cache hit count
	 */
	@ManagedMetric(category="NodeConfiguration", description="The cache hit count", metricType=MetricType.COUNTER, unit="cache-hits")
	public long getHitCount() {
		return cacheStats.getValue().hitCount();
	}
	
	/**
	 * Returns the cache miss count
	 * @return the cache miss count
	 */
	@ManagedMetric(category="NodeConfiguration", description="The cache miss count", metricType=MetricType.COUNTER, unit="cache-misses")
	public long getMissCount() {
		return cacheStats.getValue().missCount();
	}
	
	/**
	 * Returns the cache size
	 * @return the cache size
	 */
	@ManagedMetric(category="NodeConfiguration", description="The cache size", metricType=MetricType.GAUGE, unit="cache-entries")
	public long getCacheSize() {
		return contentCache.size();
	}
	
	/**
	 * Returns the cache load exception count
	 * @return the cache load exception count
	 */
	@ManagedMetric(category="NodeConfiguration", description="The cache load exception count", metricType=MetricType.COUNTER, unit="cache-load exceptions")
	public long getLoadExceptionCount() {
		return cacheStats.getValue().loadExceptionCount();
	}
	
	/**
	 * Returns the cache request count
	 * @return the cache request count
	 */
	@ManagedMetric(category="NodeConfiguration", description="The cache request count", metricType=MetricType.COUNTER, unit="cache-requests")
	public long getRequestCount() {
		return cacheStats.getValue().requestCount();
	}
	
	
	/**
	 * Returns the cache keys
	 * @return the cache keys
	 */
	@ManagedOperation(description="Returns the cache keys")
	public Set<String> cacheKeys() {
		return new HashSet<String>(contentCache.asMap().keySet());		
	}
	
	/**
	 * Invalidates the whole cache
	 */
	@ManagedOperation(description="Invalidates the whole cache")
	public void invalidateCache() {
		contentCache.invalidateAll();
	}
	
	/**
	 * Invalidates the cache configuration for the passed host/app
	 * @param host The host
	 * @param app The app
	 */
	@ManagedOperation(description="Invalidates the cache configuration for the passed host/app")
	@ManagedOperationParameters({
		@ManagedOperationParameter(name="host", description="The host to invalidate the cache entry for"),
		@ManagedOperationParameter(name="app", description="The app to invalidate the cache entry for")
	})
	public void invalidate(final String host, final String app) {
		final String key = host.trim().toLowerCase() + "/" + app.trim().toLowerCase();
		contentCache.invalidate(key);
	}
	
	
	/**
	 * Reloads the cache from the config dir
	 * @param clearFirst if true, the cache will be invalidated first
	 * @return the number of entries in the cache after this op completes
	 */
	@ManagedOperation(description="Reloads the cache from the config dir. Returns the number of entries in the cache after this op completes.")
	@ManagedOperationParameters({
		@ManagedOperationParameter(name="clearFirst", description="If true, the cache will be invalidated first")
	})	
	public long reloadContentCache(final boolean clearFirst) {
		if(clearFirst) contentCache.invalidateAll();
		reloadCache();
		log.info("Loaded [{}] KeyedFileContents", contentCache.size());
		return contentCache.size();
	}
	
	/**
	 * Returns the configuration directory
	 * @return the configuration directory
	 */
	@ManagedAttribute(description="The configuration directory")
	public String getConfigDirectory() {
		return dir.getAbsolutePath();
	}
	
	class KeyedFileContent {
		final long timestamp;
		final String content;
		final File file;
		
		/**
		 * Creates a new KeyedFileContent
		 * @param f The file the text came from
		 */
		public KeyedFileContent(final File f) {
			this.file = f;
			this.timestamp = f.lastModified();
			this.content = URLHelper.getTextFromFile(f);
		}
		
		public boolean isExpired() {
			return file.lastModified() > timestamp;
		}
		
		public String getContent() {
			return content;
		}
	}

}