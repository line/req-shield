package com.linecorp.cse.reqshield.spring3.webflux.example.configuration

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator
import com.fasterxml.jackson.databind.type.TypeFactory
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.linecorp.cse.reqshield.support.model.ReqShieldData
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisOperations
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfiguration {
    @Bean("redisOperations")
    fun <T : Any> reactiveRedisOperations(factory: ReactiveRedisConnectionFactory): ReactiveRedisOperations<String, ReqShieldData<T>> {
        val keySerializer = StringRedisSerializer()

        val serializationContext =
            RedisSerializationContext
                .newSerializationContext<String, ReqShieldData<T>>(keySerializer)
                .value(createValueSerializer(object : ParameterizedTypeReference<ReqShieldData<T>>() {}))
                .build()

        return ReactiveRedisTemplate(factory, serializationContext)
    }

    private fun <T : Any> createValueSerializer(
        typeReference: ParameterizedTypeReference<ReqShieldData<T>>,
    ): RedisSerializer<ReqShieldData<T>> {
        val javaType: JavaType = TypeFactory.defaultInstance().constructType(typeReference.type)
        return Jackson2JsonRedisSerializer<ReqShieldData<T>>(javaType).apply {
            setObjectMapper(objectMapper())
        }
    }

    @Bean("redisOperationsForGlobalLock")
    fun reactiveRedisOperationsForGlobalLock(factory: ReactiveRedisConnectionFactory): ReactiveRedisOperations<String, String> {
        val keySerializer = StringRedisSerializer()
        val valueSerializer =
            Jackson2JsonRedisSerializer(String::class.java).apply {
                setObjectMapper(objectMapper())
            }
        val serializationContext =
            RedisSerializationContext
                .newSerializationContext<String, String>(keySerializer)
                .value(valueSerializer)
                .build()

        return ReactiveRedisTemplate(factory, serializationContext)
    }

    @Bean
    fun objectMapper(): ObjectMapper =
        ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .registerModules(
                KotlinModule
                    .Builder()
                    .withReflectionCacheSize(512)
                    .configure(KotlinFeature.NullToEmptyCollection, false)
                    .configure(KotlinFeature.NullToEmptyMap, false)
                    .configure(KotlinFeature.NullIsSameAsDefault, false)
                    .configure(KotlinFeature.SingletonSupport, false)
                    .configure(KotlinFeature.StrictNullChecks, false)
                    .build(),
                JavaTimeModule(),
                Jdk8Module(),
            ).activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY,
            ).setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
}
