package br.com.forza.models.dto;

import java.util.List;

/**
 * Onde apontar o Data Out do jogo. {@code hostAddresses} é o(s) IP(s) da máquina na rede
 * local, vindos de configuração ({@code TELEMETRY_ADVERTISED_HOST}) — de dentro do container o
 * serviço só enxerga o IP da rede docker, então não dá pra descobrir sozinho. Vazio = não
 * configurado. {@code udpPort} é a porta publicada no host.
 */
public record LiveInfoDTO(List<String> hostAddresses, int udpPort) {
}
