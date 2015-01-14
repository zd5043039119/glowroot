/*
 * Copyright 2014-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.collector;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Clock;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.config.ConfigService;
import org.glowroot.config.Gauge;
import org.glowroot.jvm.LazyPlatformMBeanServer;

class GaugeCollector extends ScheduledRunnable {

    private final Logger logger;

    private final ConfigService configService;
    private final GaugePointRepository gaugePointRepository;
    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final Clock clock;
    private final long startTimeMillis;

    private final Set<String> pendingLoggedMBeanGauges = Sets.newConcurrentHashSet();
    private final Set<String> loggedMBeanGauges = Sets.newConcurrentHashSet();

    GaugeCollector(ConfigService configService, GaugePointRepository gaugePointRepository,
            LazyPlatformMBeanServer lazyPlatformMBeanServer, Clock clock, @Nullable Logger logger) {
        this.configService = configService;
        this.gaugePointRepository = gaugePointRepository;
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        this.clock = clock;
        startTimeMillis = clock.currentTimeMillis();
        if (logger == null) {
            this.logger = LoggerFactory.getLogger(GaugeCollector.class);
        } else {
            this.logger = logger;
        }
    }

    @Override
    protected void runInternal() throws Exception {
        List<GaugePoint> gaugePoints = Lists.newArrayList();
        for (Gauge gauge : configService.getGauges()) {
            gaugePoints.addAll(runInternal(gauge));
        }
        gaugePointRepository.store(gaugePoints);
    }

    @VisibleForTesting
    List<GaugePoint> runInternal(Gauge gauge) {
        ObjectName objectName;
        try {
            objectName = ObjectName.getInstance(gauge.mbeanObjectName());
        } catch (MalformedObjectNameException e) {
            logger.debug(e.getMessage(), e);
            // using toString() instead of getMessage() in order to capture exception class name
            logFirstTimeMBeanException(gauge, e.toString());
            return ImmutableList.of();
        }
        long captureTime = clock.currentTimeMillis();
        List<GaugePoint> gaugePoints = Lists.newArrayList();
        for (String mbeanAttributeName : gauge.mbeanAttributeNames()) {
            Object attributeValue;
            try {
                if (mbeanAttributeName.contains(".")) {
                    String[] path = mbeanAttributeName.split("\\.");
                    attributeValue = lazyPlatformMBeanServer.getAttribute(objectName, path[0]);
                    CompositeData compositeData = (CompositeData) attributeValue;
                    attributeValue = compositeData.get(path[1]);
                } else {
                    attributeValue =
                            lazyPlatformMBeanServer.getAttribute(objectName, mbeanAttributeName);
                }
            } catch (InstanceNotFoundException e) {
                logger.debug(e.getMessage(), e);
                // other attributes for this mbean will give same error, so log mbean not
                // found and break out of attribute loop
                logFirstTimeMBeanNotFound(gauge);
                break;
            } catch (AttributeNotFoundException e) {
                logger.debug(e.getMessage(), e);
                logFirstTimeMBeanAttributeNotFound(gauge, mbeanAttributeName);
                continue;
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
                // using toString() instead of getMessage() in order to capture exception class name
                logFirstTimeMBeanAttributeError(gauge, mbeanAttributeName, e.toString());
                continue;
            }
            if (attributeValue instanceof Number) {
                double value = ((Number) attributeValue).doubleValue();
                gaugePoints.add(ImmutableGaugePoint.builder()
                        .gaugeName(gauge.name() + "/" + mbeanAttributeName)
                        .captureTime(captureTime)
                        .value(value)
                        .build());
            } else {
                logFirstTimeMBeanAttributeError(gauge, mbeanAttributeName,
                        "MBean attribute value is not a number");
            }
        }
        return gaugePoints;
    }

    // relatively common, so nice message
    private void logFirstTimeMBeanNotFound(Gauge mbeanGauge) {
        int delaySeconds = configService.getAdvancedConfig().mbeanGaugeNotFoundDelaySeconds();
        if (clock.currentTimeMillis() - startTimeMillis < delaySeconds * 1000L) {
            pendingLoggedMBeanGauges.add(mbeanGauge.version());
        } else if (loggedMBeanGauges.add(mbeanGauge.version())) {
            if (pendingLoggedMBeanGauges.remove(mbeanGauge.version())) {
                logger.warn("mbean not found: {} (waited {} seconds after jvm startup before"
                        + " logging this warning to allow time for mbean registration"
                        + " - this wait time can be changed under Configuration > Advanced)",
                        mbeanGauge.mbeanObjectName(), delaySeconds);
            } else {
                logger.warn("mbean not found: {}", mbeanGauge.mbeanObjectName());
            }
        }
    }

    // relatively common, so nice message
    private void logFirstTimeMBeanAttributeNotFound(Gauge mbeanGauge,
            String mbeanAttributeName) {
        if (loggedMBeanGauges.add(mbeanGauge.version() + "/" + mbeanAttributeName)) {
            logger.warn("mbean attribute {} not found: {}", mbeanAttributeName,
                    mbeanGauge.mbeanObjectName());
        }
    }

    private void logFirstTimeMBeanException(Gauge mbeanGauge, @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanGauge.version())) {
            // using toString() instead of getMessage() in order to capture exception class name
            logger.warn("error accessing mbean {}: {}", mbeanGauge.mbeanObjectName(),
                    message);
        }
    }

    private void logFirstTimeMBeanAttributeError(Gauge mbeanGauge, String mbeanAttributeName,
            @Nullable String message) {
        if (loggedMBeanGauges.add(mbeanGauge.version() + "/" + mbeanAttributeName)) {
            logger.warn("error accessing mbean attribute {} {}: {}",
                    mbeanGauge.mbeanObjectName(), mbeanAttributeName, message);
        }
    }
}
