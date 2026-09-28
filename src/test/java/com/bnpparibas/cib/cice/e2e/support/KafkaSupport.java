package com.bnpparibas.cib.cice.e2e.support;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Real Kafka producer/consumer helpers against the live broker of the platform â€” no embedded
 * broker, no mock. Used to (a) act as the external producers this environment does not run
 * (C-CLIPS Account inventory / Entry manager publish {@code interest-account-intake} and
 * {@code interest-balance-intake} in production) and (b) observe the real outbox topics the
 * services publish to (cre-*, settlement-output, anomaly-events, ...).
 */
public final class KafkaSupport {

    private KafkaSupport() {
    }

    public static KafkaProducer<String, String> producer() {
        Properties props = new Properties();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, Config.kafkaBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "c-ice-e2e-producer-" + UUID.randomUUID());
        return new KafkaProducer<>(props);
    }

    /** Publishes one record with the given partition key and waits for the broker's acknowledgement (real send, not fire-and-forget). */
    public static RecordMetadata publish(KafkaProducer<String, String> producer, String topic, String key, String payload) {
        try {
            return producer.send(new ProducerRecord<>(topic, key, payload)).get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish to " + topic, e);
        }
    }

    /**
     * A consumer assigned (not subscribed â€” no consumer-group rebalance delay) to every partition of
     * {@code topic}, seeked to the current end. Create it BEFORE triggering the action under test so
     * only records produced from this point on are visible to {@link #awaitRecord}.
     */
    public static KafkaConsumer<String, String> newConsumerAtEnd(String topic) {
        Properties props = new Properties();
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, Config.kafkaBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "c-ice-e2e-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        List<PartitionInfo> partitions = consumer.partitionsFor(topic);
        List<TopicPartition> topicPartitions = partitions.stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .collect(Collectors.toList());
        consumer.assign(topicPartitions);
        consumer.seekToEnd(topicPartitions);
        // seekToEnd is lazy: force resolution now so the "end" is captured before the caller's trigger.
        topicPartitions.forEach(consumer::position);
        return consumer;
    }

    /** Polls {@code consumer} until a record matching {@code predicate} arrives, or {@code timeout} elapses. */
    public static ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, Duration timeout, Predicate<ConsumerRecord<String, String>> predicate) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (predicate.test(record)) {
                    return record;
                }
            }
        }
        throw new AssertionError("No matching record observed on the real topic within " + timeout);
    }
}
