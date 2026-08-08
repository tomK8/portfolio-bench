package com.portfolio.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Serialize non-finite doubles ({@code NaN}, {@code ±Infinity}) as JSON {@code null} instead of the
 * bare {@code NaN} token Jackson emits by default — which is invalid JSON and makes a browser's
 * {@code response.json()} throw, taking down the whole endpoint over a single empty cell.
 *
 * <p>Statistics endpoints (swing, attribution, risk) legitimately produce non-finite values for
 * empty samples (an empty quartile distribution, a bootstrap over a zero-size pool, a return over a
 * flat series). The domain code already maps most of these to nullable {@code Double}s, but the
 * primitive {@code double} fields inside records like {@code Stats.Dist} can still carry {@code NaN};
 * this customizer is the single backstop so none of them can corrupt a payload.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer nonFiniteDoublesAsNull() {
        JsonSerializer<Double> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(Double value, JsonGenerator gen, SerializerProvider provider)
                    throws IOException {
                if (value == null || value.isNaN() || value.isInfinite()) {
                    gen.writeNull();
                } else {
                    gen.writeNumber(value);
                }
            }
        };
        return builder -> builder.postConfigurer(mapper -> {
            SimpleModule module = new SimpleModule("nonFiniteDoublesAsNull");
            module.addSerializer(Double.class, serializer);
            module.addSerializer(Double.TYPE, serializer);   // primitive double fields too
            mapper.registerModule(module);
        });
    }
}
