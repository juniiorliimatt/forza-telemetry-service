package br.com.forza.models.dto;

import java.util.List;

/** Página de sessões (mais recentes primeiro). {@code nextCursor} nulo = última página. */
public record SessionPageDTO(List<SessionDTO> items, String nextCursor) {
}
