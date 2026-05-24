package io.conddo.core.notify;

/**
 * Outbound SMS seam (PRD §6.4). Auth uses it to deliver signup OTPs. The default
 * implementation is a logging stub; a real gateway (Termii / Africa's Talking /
 * Twilio …) is a small adapter swapped in when credentials exist.
 */
public interface SmsSender {

    void send(String toPhone, String message);
}
