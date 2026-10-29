package com.unihub.notification.application.port.out;

/**
 * The content of one {@code (type, channel)} for given inputs. {@code title} is the email subject
 * or the in-app title; {@code text} is the plain-text email body or the in-app body; {@code html}
 * is the HTML alternative of an email, null for a channel without one.
 */
public record RenderedNotification(String title, String text, String html) {}
