package examples;

import ai.smallest.SmallestAI;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseCloseStreamSignalMessage;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseCloseStreamSignalMessageType;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseStreamEncoding;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseStreamFormat;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseStreamItnNormalize;
import ai.smallest.resources.waves.pulsesttstreaming.types.PulseTranscriptionResponseMessage;
import ai.smallest.resources.waves.pulsesttstreaming.websocket.PulseSttStreamingConnectOptions;
import ai.smallest.resources.waves.pulsesttstreaming.websocket.PulseSttStreamingWebSocketClient;
import okio.ByteString;

/**
 * Streaming speech-to-text from a live telephony (RTP) leg, using Smallest Pulse.
 *
 * If you are coming from a pull-based streaming STT SDK (one where the engine
 * calls a read()-style callback to drain your audio), the one structural change
 * is that this client is PUSH, not pull: you forward each audio packet to
 * sendAudio(...) as it arrives, and receive interim + final transcripts via a
 * callback. Any queue / buffering you kept purely to serve a pull callback is no
 * longer needed.
 *
 * Lifecycle:
 *   connect(options)  -> open the socket (set language, encoding, sampleRate)
 *   sendAudio(...)    -> push audio, e.g. per RTP packet
 *   sendFinalize()    -> flush the current utterance and emit a final transcript
 *   sendCloseStream() -> end the session and close
 */
public final class StreamingSttTelephony {

  /** Holds the live session for one call. Create one per call/leg. */
  public static final class Session implements AutoCloseable {
    private final PulseSttStreamingWebSocketClient ws;

    public Session(SmallestAI client) {
      this.ws = client.waves().pulseSttStreaming().pulseSttStreamingWebSocket();

      // --- Transcript handler: interim results + final results ---
      ws.receiveTranscribeStreamingPulse((PulseTranscriptionResponseMessage e) -> {
        String text = e.getTranscript().orElse("");
        boolean isFinal = e.getIsFinal().orElse(false);
        if (isFinal && !text.isBlank()) {
          // Final result -> hand the transcript to your application here.
          onFinalTranscript(text);
        }
        // isFinal == false is an interim (partial) result — use it for live captions.
        // An empty final transcript means nothing was recognized for that utterance.
      });
      ws.onError(ex -> { /* log + your metric/alerting */ });
    }

    /** Open the socket. Match encoding + sampleRate to your RTP leg. */
    public void start() throws Exception {
      ws.connect(PulseSttStreamingConnectOptions.builder()
          // --- required: match these to your audio ---
          .language("en")                       // set explicitly; do not rely on the default
          .encoding(PulseStreamEncoding.MULAW)  // G.711 u-law telephony; use LINEAR16 if you decode to PCM16 first
          .sampleRate(8000)                     // telephony; server does NOT resample — must match your audio
          // --- recommended for readable, booking-friendly transcripts ---
          .format(PulseStreamFormat.TRUE)               // punctuation + capitalization
          .itnNormalize(PulseStreamItnNormalize.TRUE)   // numbers/dates/currency in written form
          // --- other optional knobs available on connect():
          //     wordTimestamps, sentenceTimestamps, vadEvents, diarize,
          //     redactPii, redactPci, keywords("Delhi,Mumbai"), eouTimeoutMs(800),
          //     finalizeOnWords, maxWords
          .build()).get();
    }

    /**
     * Feed one audio packet. Call this wherever your audio source delivers
     * bytes (e.g. per RTP packet) — you push here rather than implementing a
     * pull callback for the engine to drain.
     */
    public void onRtpPacket(byte[] audio) throws Exception {
      ws.transcribeStreamingPulseSendAudio(ByteString.of(audio)).get();
    }

    /** End of call: flush the buffer and close the socket. */
    @Override
    public void close() throws Exception {
      ws.transcribeStreamingPulseSendCloseStream(PulseCloseStreamSignalMessage.builder()
          .type(PulseCloseStreamSignalMessageType.CLOSE_STREAM).build()).get();
      ws.close();
    }

    private void onFinalTranscript(String text) {
      System.out.println("FINAL: " + text);
    }
  }

  public static void main(String[] args) throws Exception {
    SmallestAI client = SmallestAI.builder().build(); // apiKey defaults to SMALLEST_API_KEY
    try (Session session = new Session(client)) {
      session.start();
      // for (byte[] pkt : rtpLeg) session.onRtpPacket(pkt);
    }
  }

  private StreamingSttTelephony() {}
}
