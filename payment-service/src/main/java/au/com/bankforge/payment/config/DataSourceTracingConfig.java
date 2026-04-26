package au.com.bankforge.payment.config;

import io.micrometer.observation.ObservationRegistry;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import net.ttddyy.observation.tracing.DataSourceObservationListener;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

@Configuration
class DataSourceTracingConfig {

    @Bean
    static BeanPostProcessor dataSourceTracingPostProcessor(@Lazy ObservationRegistry registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource ds
                        && !(bean instanceof net.ttddyy.dsproxy.support.ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create(ds)
                            .listener(new DataSourceObservationListener(registry))
                            .build();
                }
                return bean;
            }
        };
    }
}
