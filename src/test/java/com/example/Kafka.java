package com.example;

import net.manub.embeddedkafka.EmbeddedKafka$;
import net.manub.embeddedkafka.EmbeddedKafkaConfig;
import net.manub.embeddedkafka.EmbeddedKafkaConfig$;
import scala.collection.immutable.Map$;

/**
 * EmbeddedKafka is written to be used in Scala, not from Java,
 * but we still can. This class hides the Scala specific stuff we need to do to use it.
 */
public final class Kafka {

  private static final EmbeddedKafkaConfig CONFIG = EmbeddedKafkaConfig$.MODULE$.apply(
      7899, // kafka port
      10124, // zookeeper port
      scala.collection.Map$.MODULE$.empty(), // "custom broker properties"
      scala.collection.Map$.MODULE$.empty(), // "custom producer properties"
      scala.collection.Map$.MODULE$.empty() // "custom consumer properties"

  );
  public static EmbeddedKafkaConfig getConfig() {
    return CONFIG;
  }


  final static void startKafkaServer() {
    EmbeddedKafka$.MODULE$.start(CONFIG);
  }

  final static void publishToKafka(String topic, String message) {
    EmbeddedKafka$.MODULE$.publishStringMessageToKafka(topic, message, CONFIG);
  }

  final static void createCustomTopic(String topic, int partitions, int replicationFactor) {
    EmbeddedKafka$.MODULE$.createCustomTopic(
        topic,
        Map$.MODULE$.empty(),
        partitions,
        replicationFactor,
        CONFIG
    );
  }

  final static String consumeFirstStringMessageFrom(String topic) {
    return EmbeddedKafka$.MODULE$.consumeFirstStringMessageFrom(topic, false, CONFIG);
  }

  final static void stopKafkaServer() {
    EmbeddedKafka$.MODULE$.stop();
  }

}
