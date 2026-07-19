package com.example.media.config;

import com.example.media.message.UploadVerificationMessage;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_UPLOAD_VERIFICATION = "media-upload-verification";

    @Bean
    public NewTopic uploadVerificationTopic() {
        // 3 partitions, replication factor 1 (adjust for prod)
        return new NewTopic(TOPIC_UPLOAD_VERIFICATION, 3, (short) 1);
    }

    @Bean
    public ProducerFactory<String, UploadVerificationMessage> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "${spring.kafka.bootstrap-servers}");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // optional: retries, acks, linger
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, UploadVerificationMessage> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
