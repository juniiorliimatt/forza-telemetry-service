package br.com.forza.telemetry.udp;

import br.com.forza.config.TelemetryProperties;
import br.com.forza.telemetry.ingest.IngestWorker;
import br.com.forza.telemetry.ingest.RawPacket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Recebe os datagramas do Data Out do Forza numa virtual thread dedicada e só repassa pra
 * fila do {@link IngestWorker} — sem decodificar nem tocar no banco aqui, pra o
 * {@code receive()} nunca ficar atrás do jogo (~60 pacotes/s). Desligável com
 * {@code telemetry.udp.enabled=false} (ex.: geração do OpenAPI).
 */
@Component
@ConditionalOnProperty(name = "telemetry.udp.enabled", havingValue = "true", matchIfMissing = true)
public class UdpTelemetryListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UdpTelemetryListener.class);
    private static final int MAX_DATAGRAM_BYTES = 2048;

    private final TelemetryProperties properties;
    private final IngestWorker worker;

    private volatile boolean running;
    private DatagramChannel channel;
    private Thread thread;

    public UdpTelemetryListener(final TelemetryProperties properties, final IngestWorker worker) {
        this.properties = properties;
        this.worker = worker;
    }

    @Override
    public void start() {
        final int port = properties.udp().port();
        try {
            channel = DatagramChannel.open();
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.setOption(StandardSocketOptions.SO_RCVBUF, properties.udp().receiveBufferBytes());
            channel.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível abrir a porta UDP " + port + " (já em uso?)", e);
        }
        running = true;
        thread = Thread.ofVirtual().name("udp-telemetry-receiver").start(this::receiveLoop);
        log.info("Escutando telemetria Forza em UDP 0.0.0.0:{}", port);
    }

    @Override
    public void stop() {
        running = false;
        try {
            channel.close();
        } catch (IOException e) {
            log.warn("Falha ao fechar o canal UDP", e);
        }
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Fase maior que a do worker: sobe depois dele e, no shutdown, para antes (deixa o worker drenar a fila). */
    @Override
    public int getPhase() {
        return 200;
    }

    private void receiveLoop() {
        final ByteBuffer buffer = ByteBuffer.allocate(MAX_DATAGRAM_BYTES);
        while (running) {
            buffer.clear();
            try {
                channel.receive(buffer);
            } catch (ClosedChannelException e) {
                // inclui AsynchronousCloseException: canal fechado por stop()
                return;
            } catch (IOException e) {
                log.warn("Erro lendo datagrama UDP", e);
                continue;
            }
            final long receivedNanos = System.nanoTime();
            buffer.flip();
            final byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            worker.offer(new RawPacket(bytes, receivedNanos));
        }
    }
}
