/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.visualizers.backend.graphite;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.apache.jmeter.visualizers.backend.SamplerMetric;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

/**
 * Graphite based Listener using Pickle Protocol
 * @see <a href="http://graphite.readthedocs.org/en/latest/overview.html">Graphite Overview</a>
 * @since 2.13
 */
public class GraphiteBackendListenerClient extends AbstractBackendListenerClient implements Runnable {

    //+ Argument names
    // These are stored in the JMX file, so DO NOT CHANGE ANY VALUES
    private static final String GRAPHITE_METRICS_SENDER = "graphiteMetricsSender"; //$NON-NLS-1$
    private static final String GRAPHITE_HOST = "graphiteHost"; //$NON-NLS-1$
    private static final String GRAPHITE_PORT = "graphitePort"; //$NON-NLS-1$
    private static final String ROOT_METRICS_PREFIX = "rootMetricsPrefix"; //$NON-NLS-1$
    private static final String PERCENTILES = "percentiles"; //$NON-NLS-1$
    private static final String SAMPLERS_LIST = "samplersList"; //$NON-NLS-1$
    public static final String USE_REGEXP_FOR_SAMPLERS_LIST = "useRegexpForSamplersList"; //$NON-NLS-1$
    public static final String USE_REGEXP_FOR_SAMPLERS_LIST_DEFAULT = "false";
    private static final String SUMMARY_ONLY = "summaryOnly"; //$NON-NLS-1$
    //- Argument names

    private static final int DEFAULT_PLAINTEXT_PROTOCOL_PORT = 2003;
    private static final String TEST_CONTEXT_NAME = "test";
    private static final String ALL_CONTEXT_NAME = "all";

    private static final Logger LOGGER = LoggingManager.getLoggerForClass();
    private static final String DEFAULT_METRICS_PREFIX = "jmeter."; //$NON-NLS-1$
    private static final String CUMULATED_METRICS = "__cumulated__"; //$NON-NLS-1$
    // User Metrics
    private static final String METRIC_MAX_ACTIVE_THREADS = "maxAT"; //$NON-NLS-1$
    private static final String METRIC_MIN_ACTIVE_THREADS = "minAT"; //$NON-NLS-1$
    private static final String METRIC_MEAN_ACTIVE_THREADS = "meanAT"; //$NON-NLS-1$
    private static final String METRIC_STARTED_THREADS = "startedT"; //$NON-NLS-1$
    private static final String METRIC_FINISHED_THREADS = "endedT"; //$NON-NLS-1$

    // Response time Metrics
    private static final String METRIC_SEPARATOR = "."; //$NON-NLS-1$
    private static final String METRIC_OK_PREFIX = "ok"; //$NON-NLS-1$
    private static final String METRIC_KO_PREFIX = "ko"; //$NON-NLS-1$
    private static final String METRIC_ALL_PREFIX = "a"; //$NON-NLS-1$
    private static final String METRIC_HITS_PREFIX = "h"; //$NON-NLS-1$

    private static final String METRIC_STANDARD_DEVIATION = "stddev"; //$NON-NLS-1$
    private static final String METRIC_COUNT = "count"; //$NON-NLS-1$
    private static final String METRIC_MIN_RESPONSE_TIME = "min"; //$NON-NLS-1$
    private static final String METRIC_MAX_RESPONSE_TIME = "max"; //$NON-NLS-1$
    private static final String METRIC_AVG_RESPONSE_TIME = "avg"; //$NON-NLS-1$
    private static final String METRIC_PERCENTILE = "pct"; //$NON-NLS-1$

    private static final String METRIC_OK_STANDARD_DEVIATION = METRIC_OK_PREFIX+METRIC_SEPARATOR+METRIC_STANDARD_DEVIATION;
    private static final String METRIC_OK_COUNT              = METRIC_OK_PREFIX+METRIC_SEPARATOR+METRIC_COUNT;
    private static final String METRIC_OK_MIN_RESPONSE_TIME  = METRIC_OK_PREFIX+METRIC_SEPARATOR+METRIC_MIN_RESPONSE_TIME;
    private static final String METRIC_OK_MAX_RESPONSE_TIME  = METRIC_OK_PREFIX+METRIC_SEPARATOR+METRIC_MAX_RESPONSE_TIME;
    private static final String METRIC_OK_AVG_RESPONSE_TIME  = METRIC_OK_PREFIX+METRIC_SEPARATOR+METRIC_AVG_RESPONSE_TIME;
    private static final String METRIC_OK_PERCENTILE_PREFIX  = METRIC_OK_PREFIX+METRIC_SEPARATOR+METRIC_PERCENTILE;

    private static final String METRIC_KO_STANDARD_DEVIATION = METRIC_KO_PREFIX+METRIC_SEPARATOR+METRIC_STANDARD_DEVIATION;
    private static final String METRIC_KO_COUNT              = METRIC_KO_PREFIX+METRIC_SEPARATOR+METRIC_COUNT;
    private static final String METRIC_KO_MIN_RESPONSE_TIME  = METRIC_KO_PREFIX+METRIC_SEPARATOR+METRIC_MIN_RESPONSE_TIME;
    private static final String METRIC_KO_MAX_RESPONSE_TIME  = METRIC_KO_PREFIX+METRIC_SEPARATOR+METRIC_MAX_RESPONSE_TIME;
    private static final String METRIC_KO_AVG_RESPONSE_TIME  = METRIC_KO_PREFIX+METRIC_SEPARATOR+METRIC_AVG_RESPONSE_TIME;
    private static final String METRIC_KO_PERCENTILE_PREFIX  = METRIC_KO_PREFIX+METRIC_SEPARATOR+METRIC_PERCENTILE;

    private static final String METRIC_ALL_STANDARD_DEVIATION = METRIC_ALL_PREFIX+METRIC_SEPARATOR+METRIC_STANDARD_DEVIATION;
    private static final String METRIC_ALL_COUNT              = METRIC_ALL_PREFIX+METRIC_SEPARATOR+METRIC_COUNT;
    private static final String METRIC_ALL_MIN_RESPONSE_TIME  = METRIC_ALL_PREFIX+METRIC_SEPARATOR+METRIC_MIN_RESPONSE_TIME;
    private static final String METRIC_ALL_MAX_RESPONSE_TIME  = METRIC_ALL_PREFIX+METRIC_SEPARATOR+METRIC_MAX_RESPONSE_TIME;
    private static final String METRIC_ALL_AVG_RESPONSE_TIME  = METRIC_ALL_PREFIX+METRIC_SEPARATOR+METRIC_AVG_RESPONSE_TIME;
    private static final String METRIC_ALL_PERCENTILE_PREFIX  = METRIC_ALL_PREFIX+METRIC_SEPARATOR+METRIC_PERCENTILE;

    private static final String METRIC_ALL_HITS_COUNT        = METRIC_HITS_PREFIX+METRIC_SEPARATOR+METRIC_COUNT;

    private static final long ONE_SECOND = 1L;
    private static final int MAX_POOL_SIZE = 1;
    private static final String DEFAULT_PERCENTILES = "90;95;99";
    private static final String SEPARATOR = ";"; //$NON-NLS-1$
    private static final Object LOCK = new Object();

    private String graphiteHost;
    private int graphitePort;
    private boolean summaryOnly;
    private String rootMetricsPrefix;
    private String samplersList = ""; //$NON-NLS-1$
    private boolean useRegexpForSamplersList;
    private Set<String> samplersToFilter;
    private Map<String, Float> okPercentiles;
    private Map<String, Float> koPercentiles;
    private Map<String, Float> allPercentiles;

    private List<SampleResult> otherSampleResults;
    private List<SampleResult> sameQuerySampleResults;

    private GraphiteMetricsSender graphiteMetricsManager;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> timerHandle;

    private Pattern pattern;

    public GraphiteBackendListenerClient() {
        super();
    }

    @Override
    public void run() {
        sendMetrics();
    }

    /**
     * Send metrics to Graphite
     */
    protected void sendMetrics() {

        // Need to convert millis to seconds for Graphite
        long timestampInSeconds = TimeUnit.SECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        synchronized (LOCK) {
            for (Map.Entry<String, SamplerMetric> entry : getMetricsPerSampler().entrySet()) {
                final String key = entry.getKey();
                final SamplerMetric metric = entry.getValue();
                if (!key.equals(CUMULATED_METRICS)) {
                    addMetrics(timestampInSeconds, AbstractGraphiteMetricsSender.sanitizeString(key), metric);
                } else {
                    // that is where "ALL" metrics come from -- we don't need it
                    //addMetrics(timestampInSeconds, ALL_CONTEXT_NAME, metric);
                }
                // We are computing on interval basis so cleanup
                metric.resetForTimeInterval();
            }
        }

        // after we call it -- all collected metrics in grMetManager are GONE!
        graphiteMetricsManager.writeAndSendMetrics();
    }


    /**
     * Add request metrics to metrics manager.
     * Note if total number of requests is 0, no response time metrics are sent.
     * @param timestampInSeconds long
     * @param contextName String
     * @param metric {@link SamplerMetric}
     */
    private void addMetrics(long timestampInSeconds, String contextName, SamplerMetric metric) {

        // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57350
        if(metric.getTotal() > 0) {
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_OK_COUNT, Integer.toString(metric.getSuccesses()));
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_KO_COUNT, Integer.toString(metric.getFailures()));
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_ALL_COUNT, Integer.toString(metric.getTotal()));
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_ALL_HITS_COUNT, Integer.toString(metric.getHits()));
            if(metric.getSuccesses()>0) {
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_OK_STANDARD_DEVIATION, Double.toString(metric.getOkStandardDeviation()));
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_OK_MIN_RESPONSE_TIME, Double.toString(metric.getOkMinTime()));
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_OK_MAX_RESPONSE_TIME, Double.toString(metric.getOkMaxTime()));
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_OK_AVG_RESPONSE_TIME, Double.toString(metric.getOkMean()));
                for (Map.Entry<String, Float> entry : okPercentiles.entrySet()) {
                    graphiteMetricsManager.addMetric(timestampInSeconds, contextName,
                            entry.getKey(),
                            Double.toString(metric.getOkPercentile(entry.getValue().floatValue())));
                }
            }
            if(metric.getFailures()>0) {
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_KO_STANDARD_DEVIATION, Double.toString(metric.getKoStandardDeviation()));
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_KO_MIN_RESPONSE_TIME, Double.toString(metric.getKoMinTime()));
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_KO_MAX_RESPONSE_TIME, Double.toString(metric.getKoMaxTime()));
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_KO_AVG_RESPONSE_TIME, Double.toString(metric.getKoMean()));
                for (Map.Entry<String, Float> entry : koPercentiles.entrySet()) {
                    graphiteMetricsManager.addMetric(timestampInSeconds, contextName,
                            entry.getKey(),
                            Double.toString(metric.getKoPercentile(entry.getValue().floatValue())));
                }
            }
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_ALL_STANDARD_DEVIATION, Double.toString(metric.getAllStandardDeviation()));
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_ALL_MIN_RESPONSE_TIME, Double.toString(metric.getAllMinTime()));
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_ALL_MAX_RESPONSE_TIME, Double.toString(metric.getAllMaxTime()));
            graphiteMetricsManager.addMetric(timestampInSeconds, contextName, METRIC_ALL_AVG_RESPONSE_TIME, Double.toString(metric.getAllMean()));
            for (Map.Entry<String, Float> entry : allPercentiles.entrySet()) {
                graphiteMetricsManager.addMetric(timestampInSeconds, contextName,
                        entry.getKey(),
                        Double.toString(metric.getAllPercentile(entry.getValue().floatValue())));
            }
        }
    }

    /**
     * @return the samplersList
     */
    public String getSamplersList() {
        return samplersList;
    }

    /**
     * @param samplersList the samplersList to set
     */
    public void setSamplersList(String samplersList) {
        this.samplersList = samplersList;
    }

    @Override
    public void handleSampleResults(List<SampleResult> sampleResults,
                                    BackendListenerContext context) {
        synchronized (LOCK) {

            String firstMetricName = sameQuerySampleResults.size() > 0 ?
                    sameQuerySampleResults.get(0).getSampleLabel() : null;
            String lastMetricName;
            int counter;

            do {
                counter = 1;
                lastMetricName = sampleResults.get(0).getSampleLabel();

                for (SampleResult sampleResult : sampleResults) {
                    if (! lastMetricName.equals(sampleResult.getSampleLabel())) {
                        // counting how many there are different types of metrics
                        lastMetricName = sampleResult.getSampleLabel();
                        counter++;
                    }

                    if (firstMetricName == null) {
                        firstMetricName = sampleResult.getSampleLabel();
                        sameQuerySampleResults.add(sampleResult);
                        continue;
                    }

                    if (firstMetricName.equals(sampleResult.getSampleLabel())) {
                        sameQuerySampleResults.add(sampleResult);
                    } else {
                        otherSampleResults.add(sampleResult);
                    }
                }

                if (otherSampleResults.size() != 0) {
                    getSampleMetricsFromSampleResults();
                    sendMetrics();
                    firstMetricName = null;
                }

                sampleResults = new ArrayList<>(otherSampleResults);
                otherSampleResults.clear();
            } while (counter > 1 || sampleResults.size() != 0);
        }
    }

    private void getSampleMetricsFromSampleResults() {
        boolean samplersToFilterMatch;
        synchronized (LOCK) {
            for (SampleResult sampleResult : sameQuerySampleResults) {
                if(!summaryOnly) {
                    if (useRegexpForSamplersList) {
                        Matcher matcher = pattern.matcher(sampleResult.getSampleLabel());
                        samplersToFilterMatch = matcher.matches();
                    } else {
                        samplersToFilterMatch = samplersToFilter.contains(sampleResult.getSampleLabel());
                    }
                    if (samplersToFilterMatch) {
                        SamplerMetric samplerMetric = getSamplerMetric(sampleResult.getSampleLabel());
                        samplerMetric.add(sampleResult);
                    }
                }
                SamplerMetric cumulatedMetrics = getSamplerMetric(CUMULATED_METRICS);
                cumulatedMetrics.add(sampleResult);
            }
            sameQuerySampleResults.clear();
        }
    }

    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        String graphiteMetricsSenderClass = context.getParameter(GRAPHITE_METRICS_SENDER);

        graphiteHost = context.getParameter(GRAPHITE_HOST);
        graphitePort = context.getIntParameter(GRAPHITE_PORT, DEFAULT_PLAINTEXT_PROTOCOL_PORT);
        summaryOnly = context.getBooleanParameter(SUMMARY_ONLY, true);
        samplersList = context.getParameter(SAMPLERS_LIST, "");
        useRegexpForSamplersList = context.getBooleanParameter(USE_REGEXP_FOR_SAMPLERS_LIST, false);
        rootMetricsPrefix = context.getParameter(ROOT_METRICS_PREFIX, DEFAULT_METRICS_PREFIX);
        String[]  percentilesStringArray = context.getParameter(PERCENTILES, DEFAULT_METRICS_PREFIX).split(SEPARATOR);
        okPercentiles = new HashMap<>(percentilesStringArray.length);
        koPercentiles = new HashMap<>(percentilesStringArray.length);
        allPercentiles = new HashMap<>(percentilesStringArray.length);
        DecimalFormat decimalFormat = new DecimalFormat("0.##");
        for (String percentilesString : percentilesStringArray) {
            if (!StringUtils.isEmpty(percentilesString.trim())) {
                try {
                    Float percentileValue = Float.valueOf(percentilesString.trim());
                    String sanitizedFormattedPercentile =
                            AbstractGraphiteMetricsSender.sanitizeString(
                                    decimalFormat.format(percentileValue));
                    okPercentiles.put(
                            METRIC_OK_PERCENTILE_PREFIX + sanitizedFormattedPercentile,
                            percentileValue);
                    koPercentiles.put(
                            METRIC_KO_PERCENTILE_PREFIX + sanitizedFormattedPercentile,
                            percentileValue);
                    allPercentiles.put(
                            METRIC_ALL_PERCENTILE_PREFIX + sanitizedFormattedPercentile,
                            percentileValue);

                } catch (Exception e) {
                    LOGGER.error("Error parsing percentile:'" + percentilesString + "'", e);
                }
            }
        }
        Class<?> clazz = Class.forName(graphiteMetricsSenderClass);
        this.graphiteMetricsManager = (GraphiteMetricsSender) clazz.newInstance();
        graphiteMetricsManager.setup(graphiteHost, graphitePort, rootMetricsPrefix);

        otherSampleResults = new ArrayList<>();
        sameQuerySampleResults = new ArrayList<>();

        if (useRegexpForSamplersList) {
            pattern = Pattern.compile(samplersList);
        } else {
            String[] samplers = samplersList.split(SEPARATOR);
            samplersToFilter = new HashSet<>();
            Collections.addAll(samplersToFilter, samplers);
        }
        scheduler = Executors.newScheduledThreadPool(MAX_POOL_SIZE);
        // Don't change this as metrics are per second
        this.timerHandle = scheduler.scheduleAtFixedRate(this, ONE_SECOND, ONE_SECOND, TimeUnit.SECONDS);
    }

    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        boolean cancelState = timerHandle.cancel(false);
        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("Canceled state:"+cancelState);
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("Error waiting for end of scheduler");
        }
        // Send last set of data before ending
        getSampleMetricsFromSampleResults();
        sendMetrics();

        samplersToFilter.clear();
        graphiteMetricsManager.destroy();
        super.teardownTest(context);
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments arguments = new Arguments();
        arguments.addArgument(GRAPHITE_METRICS_SENDER, TextGraphiteMetricsSender.class.getName());
        arguments.addArgument(GRAPHITE_HOST, "");
        arguments.addArgument(GRAPHITE_PORT, Integer.toString(DEFAULT_PLAINTEXT_PROTOCOL_PORT));
        arguments.addArgument(ROOT_METRICS_PREFIX, DEFAULT_METRICS_PREFIX);
        arguments.addArgument(SUMMARY_ONLY, "true");
        arguments.addArgument(SAMPLERS_LIST, "");
        arguments.addArgument(USE_REGEXP_FOR_SAMPLERS_LIST, USE_REGEXP_FOR_SAMPLERS_LIST_DEFAULT);
        arguments.addArgument(PERCENTILES, DEFAULT_PERCENTILES);
        return arguments;
    }
}
