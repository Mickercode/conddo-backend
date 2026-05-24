package io.conddo.api.web.dto;

import io.conddo.core.service.OrderService.Board;

import java.util.List;

/** Kanban board (§11.4): ordered stage columns, each with a count and its cards. */
public record BoardResponse(List<Column> stages) {

    public record Column(String name, long count, List<OrderCard> orders) {
    }

    public static BoardResponse from(Board board) {
        List<Column> columns = board.stages().stream()
                .map(c -> new Column(c.name(), c.orders().size(),
                        c.orders().stream().map(OrderCard::from).toList()))
                .toList();
        return new BoardResponse(columns);
    }
}
