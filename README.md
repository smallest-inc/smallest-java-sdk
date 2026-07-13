# Smallest AI Java SDK

Official Java client for the [Smallest AI](https://smallest.ai) API — Waves (TTS / STT) and Atoms (voice agents).

Generated with [Fern](https://buildwithfern.com) from the same API definition as the [Python](https://github.com/smallest-inc/smallest-python-sdk), Node, and Go SDKs.

## Requirements

- Java 17+

## Install

The SDK depends on OkHttp and Jackson. With Gradle:

```gradle
dependencies {
    implementation 'ai.smallest:smallest-java-sdk:0.1.0'
}
```

## Usage

```java
import ai.smallest.SmallestAI;
import ai.smallest.resources.waves.types.TtsRequest;
import ai.smallest.resources.waves.types.TtsRequestOutputFormat;

SmallestAI client = SmallestAI.builder()
    .apiKey(System.getenv("SMALLEST_API_KEY")) // defaults to the env var
    .build();

// Text-to-speech
var audio = client.waves().synthesizeTts(
    TtsRequest.builder()
        .text("The quick brown fox jumps over the lazy dog.")
        .voiceId("avery")
        .outputFormat(TtsRequestOutputFormat.WAV)
        .sampleRate(16000)
        .build());
```

Get an API key at [app.smallest.ai](https://app.smallest.ai/dashboard/api-keys).

## Status

Initial `0.1.0` snapshot. Real-time streaming STT/TTS WebSocket clients are included and evolving.
