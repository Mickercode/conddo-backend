package io.conddo.payments.common;

/**
 * RoutePay refused the request / timed out / returned 5xx. Mapped to 502
 * {@code ROUTEPAY_UNAVAILABLE} — the FE shows "Payments are temporarily
 * unavailable, please try again" and the order/booking stays unpaid.
 */
public class RoutePayUnavailableException extends RuntimeException {
    public RoutePayUnavailableException(String message) {
        super(message);
    }

    public RoutePayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
