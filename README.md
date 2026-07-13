# Smallest AI Java SDK

Official Java client for the [Smallest AI](https://smallest.ai) API — Waves (TTS / STT) and Atoms (voice agents).

Generated with [Fern](https://buildwithfern.com) from the same API definition as the [Python](https://github.com/smallest-inc/smallest-python-sdk), Node, and Go SDKs.

## Requirements

- Java 17+

## Install

Available via [JitPack](https://jitpack.io). OkHttp and Jackson are pulled in transitively.

**Gradle**
```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.smallest-inc:smallest-java-sdk:0.1.3'
}
```

**Maven**
```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.smallest-inc</groupId>
  <artifactId>smallest-java-sdk</artifactId>
  <version>0.1.3</version>
</dependency>
```

## Client

```java
import ai.smallest.SmallestAI;

SmallestAI client = SmallestAI.builder()
    .apiKey(System.getenv("SMALLEST_API_KEY")) // defaults to the env var
    .build();
```

Get an API key at [app.smallest.ai](https://app.smallest.ai/dashboard/api-keys).

## Text-to-speech

```java
import ai.smallest.resources.waves.types.TtsRequest;
import ai.smallest.resources.waves.types.TtsRequestOutputFormat;

var audio = client.waves().synthesizeTts(
    TtsRequest.builder()
        .text("The quick brown fox jumps over the lazy dog.")
        .voiceId("avery")
        .outputFormat(TtsRequestOutputFormat.WAV)
        .sampleRate(16000)
        .build()); // InputStream of audio
```

## Speech-to-text (file / batch)

```java
import ai.smallest.resources.waves.requests.TranscribePulseWavesRequest;
import ai.smallest.resources.waves.types.TranscribePulseWavesRequestLanguage;

var resp = client.waves().transcribePulse(
    TranscribePulseWavesRequest.builder()
        .body(audioBytes)
        .language(TranscribePulseWavesRequestLanguage.EN) // set explicitly
        .build());
System.out.println(resp.getTranscription().orElse(""));
```

## Streaming speech-to-text (real-time / telephony)

Push audio as it arrives and receive interim + final transcripts over a WebSocket.
Match `encoding` and `sampleRate` to your audio — **the server does not resample.**

```java
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseStreamEncoding;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseFinalizeSignalMessage;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseFinalizeSignalMessageType;
import ai.smallest.resources.waves.pulsesttstreaming.websocket.PulseSttStreamingConnectOptions;
import okio.ByteString;

var stt = client.waves().pulseSttStreaming().pulseSttStreamingWebSocket();

stt.receiveTranscribeStreamingPulse(e -> {
    if (e.getIsFinal().orElse(false)) {
        System.out.println("final: " + e.getTranscript().orElse(""));
    }
});
stt.onError(ex -> System.err.println(ex.getMessage()));

stt.connect(PulseSttStreamingConnectOptions.builder()
    .language("en")
    .encoding(PulseStreamEncoding.MULAW) // G.711 u-law telephony; LINEAR16 for raw PCM16
    .sampleRate(8000)                    // 8000 for telephony
    .eouTimeoutMs(500)                   // faster end-of-utterance for agent turn-taking
    .keywords("Smallest:6,Pulse:6")      // boost domain terms (streaming only)
    .build()).get();

// feed audio as it arrives (e.g. per RTP packet), then finalize
stt.transcribeStreamingPulseSendAudio(ByteString.of(audioChunk)).get();
stt.transcribeStreamingPulseSendFinalize(
    PulseFinalizeSignalMessage.builder()
        .type(PulseFinalizeSignalMessageType.FINALIZE).build()).get();
```

Other session options on the builder: `sentenceTimestamps`, `diarize`, `redactPii`, `redactPci`,
`punctuate`, `capitalize`, `format`, `itnNormalize`, `endpointing`, `finalizeOnWords`, `maxWords`,
`wordTimestamps`, `vadEvents`. New server params not yet typed can be passed with
`.additionalProperty("name", value)` — they are forwarded as query parameters.

A full runnable telephony (8 kHz μ-law) example is in
[`examples/StreamingSttTelephony.java`](examples/StreamingSttTelephony.java).
Migrating from Azure Speech SDK? See
[`examples/AzureStyleSttRecognizer.java`](examples/AzureStyleSttRecognizer.java) for a
recognizing/recognized/canceled event-style wrapper.

## Generation and maintenance

This SDK is generated with `fernapi/fern-java-sdk` from the Fern API definition in
[smallest-ai-documentation](https://github.com/smallest-inc/smallest-ai-documentation)
(`fern/apis/unified`). Generation metadata (generator version, origin spec commit) is
recorded in `.fern/metadata.json`.

**Current state: generated manually, with hand patches.** The Java SDK is not yet wired
into the Fern CI pipeline (`generators.yml` has no `java-sdk` group), and a small set of
generated files carry manual fixes that are not yet in the spec or generator. The full
list, with the reason for each patch and where it must be upstreamed, lives in
[`.fernignore`](.fernignore) — the same convention the
[Python SDK](https://github.com/smallest-inc/smallest-python-sdk) uses to protect
customer-owned files from regeneration.

Rules for maintainers:

- Do not regenerate over the files listed in `.fernignore` until their patches are
  upstreamed (spec changes go to `smallest-ai-documentation`; generator bugs go to Fern
  support). Regenerating first silently reintroduces known bugs (see `.fernignore`).
- Hand-written files (`examples/`, `build.gradle`, this README, etc.) are always
  customer-owned and listed in `.fernignore`.
- End state: add a `java-sdk` group to `fern/apis/unified/generators.yml` with
  `github: mode: pull-request` (like the Python SDK), upstream the streaming session
  params into the AsyncAPI ws bindings, and shrink `.fernignore` down to the
  hand-written files only.

## Status

Initial `0.1.x` snapshot. Real-time streaming STT/TTS WebSocket clients are included and evolving.
