package com.example;

import java.nio.charset.StandardCharsets;

public class Parsing {

  // marker interface/ADT top type with exactly two implementations below
  interface ParsedRecord {
    String destinationIndex();
  }

  public static final class MonitoringMessage implements ParsedRecord {
    private final String field;

    @Override
    public String destinationIndex() {
      return "normal_messages";
    }

    public MonitoringMessage(String field) {
      this.field = field;
    }

    public String getField() {
      return field;
    }
  }

  public static class NotParseable implements ParsedRecord {
    private final String rawData;
    private final String parseError;
    public NotParseable(String rawData, String parseError) {
      this.rawData = rawData;
      this.parseError = parseError;
    }

    @Override
    public String destinationIndex() {
      return "failed_messages";
    }

    public String getRawData() {
      return rawData;
    }
    public String getParseError() {
      return parseError;
    }
  }


  public static ParsedRecord parseKafkaMessage(byte[] bytes) {
    // parsing, this could potentially throw an exception if the message cannot be parsed
    // throw new RuntimeException("argh, not deserializable!");
    String textPayload = new String(bytes, StandardCharsets.UTF_8);
    if (textPayload.equals("bad data"))
      return new NotParseable(textPayload, "That is impossible to parse!");
    else
      return new MonitoringMessage(textPayload);
  }

}
