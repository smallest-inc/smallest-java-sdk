package examples;

import ai.smallest.SmallestAI;
import ai.smallest.core.DisconnectReason;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseCloseStreamSignalMessage;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseCloseStreamSignalMessageType;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseStreamEncoding;
import ai.smallest.resources.waves.pulsesttstreaming.websocket.PulseSttStreamingConnectOptions;
import ai.smallest.resources.waves.pulsesttstreaming.websocket.PulseSttStreamingWebSocketClient;
import okio.ByteString;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Drop-in event-style wrapper for teams migrating from the Azure Speech SDK.
 *
 * Azure's SpeechRecognizer exposes recognizing / recognized / canceled /
 * sessionStopped events. This adapter maps the Smallest Pulse streaming STT
 * WebSocket onto the same shape so an existing Azure integration can switch
 * providers with minimal code movement:
 *
 *   Azure                         This adapter
 *   -----                         ------------
 *   recognizer.recognizing        recognizer.recognizing(text -> ...)   // interim
 *   recognizer.recognized         recognizer.recognized(text -> ...)    // is_final
 *   recognizer.canceled           recognizer.canceled(err -> ...)
 *   recognizer.sessionStopped     recognizer.sessionStopped(r -> ...)
 *   PushAudioInputStream.write    recognizer.writeAudio(bytes)
 *   startContinuousRecognition    recognizer.start()
 *   stopContinuousRecognition     recognizer.stop()
 *
 * Usage (telephony, G.711 u-law 8 kHz):
 *
 *   var recognizer = new AzureStyleSttRecognizer(
 *       SmallestAI.builder().build(),
 *       AzureStyleSttRecognizer.Config.telephony("en"));
 *   recognizer.recognizing(text -> render(text));
 *   recognizer.recognized(text -> handleFinal(text));
 *   recognizer.canceled(err -> log(err));
 *   recognizer.start().join();
 *   // per RTP packet:
 *   recognizer.writeAudio(packetBytes);
 *   // on hangup:
 *   recognizer.stop();
 */
public final class AzureStyleSttRecognizer implements AutoCloseable {

  public record Config(String language, PulseStreamEncoding encoding, int sampleRate,
                       Integer eouTimeoutMs, String keywords) {
    /** 8 kHz G.711 u-law, tuned for fast agent turn-taking. */
    public static Config telephony(String language) {
      return new Config(language, PulseStreamEncoding.MULAW, 8000, 500, null);
    }

    /** 16 kHz PCM16, e.g. microphone or decoded audio. */
    public static Config pcm16k(String language) {
      return new Config(language, PulseStreamEncoding.LINEAR16, 16000, null, null);
    }
  }

  private final PulseSttStreamingWebSocketClient ws;
  private final Config config;

  private volatile Consumer<String> recognizing = t -> {};
  private volatile Consumer<String> recognized = t -> {};
  private volatile Consumer<Exception> canceled = e -> {};
  private volatile Consumer<DisconnectReason> sessionStopped = r -> {};

  public AzureStyleSttRecognizer(SmallestAI client, Config config) {
    this.config = config;
    this.ws = client.waves().pulseSttStreaming().pulseSttStreamingWebSocket();

    ws.receiveTranscribeStreamingPulse(m -> {
      String text = m.getTranscript().orElse("");
      if (m.getIsFinal().orElse(false)) {
        if (!text.isBlank()) recognized.accept(text);
      } else {
        recognizing.accept(text);
      }
    });
    ws.onError(e -> canceled.accept(e));
    ws.onDisconnected(r -> sessionStopped.accept(r));
  }

  public void recognizing(Consumer<String> handler) { this.recognizing = handler; }

  public void recognized(Consumer<String> handler) { this.recognized = handler; }

  public void canceled(Consumer<Exception> handler) { this.canceled = handler; }

  public void sessionStopped(Consumer<DisconnectReason> handler) { this.sessionStopped = handler; }

  /** Equivalent of startContinuousRecognitionAsync(). */
  public CompletableFuture<Void> start() {
    var opts = PulseSttStreamingConnectOptions.builder()
        .language(config.language())
        .encoding(config.encoding())
        .sampleRate(config.sampleRate());
    if (config.eouTimeoutMs() != null) opts.eouTimeoutMs(config.eouTimeoutMs());
    if (config.keywords() != null) opts.keywords(config.keywords());
    return ws.connect(opts.build());
  }

  /** Equivalent of PushAudioInputStream.write(). Push audio as it arrives. */
  public void writeAudio(byte[] audio) {
    ws.transcribeStreamingPulseSendAudio(ByteString.of(audio));
  }

  /**
   * Equivalent of stopContinuousRecognitionAsync(): asks the server to flush and
   * deliver the last transcript (is_last=true), then closes the socket.
   */
  public void stop() throws Exception {
    ws.transcribeStreamingPulseSendCloseStream(PulseCloseStreamSignalMessage.builder()
        .type(PulseCloseStreamSignalMessageType.CLOSE_STREAM).build()).get();
    // Give the server a moment to deliver the final transcript before closing.
    Thread.sleep(1000);
    ws.close();
  }

  @Override
  public void close() throws Exception {
    stop();
  }
}
