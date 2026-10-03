package br.com.forza.telemetry.ingest;

/** Datagrama UDP cru com o instante de chegada ({@link System#nanoTime()}), carimbado pela thread de recepção. */
public record RawPacket(byte[] data, long receivedNanos) {
}
