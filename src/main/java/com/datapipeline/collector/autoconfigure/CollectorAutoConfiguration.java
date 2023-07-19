package com.datapipeline.collector.autoconfigure;

import com.datapipeline.collector.metric.Config;
import com.datapipeline.collector.metric.MetricReporter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration(
        proxyBeanMethods = false
)
@EnableConfigurationProperties({Config.class})
public class CollectorAutoConfiguration {

    private final Config config;

    public CollectorAutoConfiguration(Config config) {
        this.config = config;
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricReporter metricReporter() {
        MetricReporter metricReporter = new MetricReporter(this.config);
        metricReporter.initEnv();
        return metricReporter;
    }
}
