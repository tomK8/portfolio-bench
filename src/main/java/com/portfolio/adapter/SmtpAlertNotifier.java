package com.portfolio.adapter;

import com.portfolio.port.AlertNotifier;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Sends watchlist alerts over SMTP (Gmail, via a Google app password). Uses Jakarta Mail
 * directly rather than Spring's {@code JavaMailSender} so this adapter stays framework-free
 * like the rest of {@code com.portfolio.adapter}; the SMTP coordinates arrive as a
 * {@link Settings} record wired from {@code application.properties} in
 * {@code BeanConfiguration}.
 *
 * <p>Never throws: a delivery failure is logged and reported as {@code false} so a flaky mail
 * server can't take down the scheduler. Disabled (the default) until the user fills in the
 * {@code portfolio.alert.mail.*} settings — {@link #isEnabled()} gates the whole path.
 */
public class SmtpAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(SmtpAlertNotifier.class);

    private final Settings settings;

    public SmtpAlertNotifier(Settings settings) {
        this.settings = settings;
    }

    @Override
    public boolean isEnabled() {
        return settings.enabled()
                && notBlank(settings.host()) && notBlank(settings.from()) && notBlank(settings.to());
    }

    @Override
    public boolean notify(String subject, String body) {
        if (!isEnabled()) {
            log.debug("Alert mail disabled — skipping '{}'", subject);
            return false;
        }
        try {
            Session session = buildSession();
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(settings.from()));
            for (String recipient : settings.to().split("\\s*,\\s*")) {
                if (!recipient.isBlank()) {
                    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient.trim()));
                }
            }
            msg.setSubject(subject);
            msg.setText(body);
            Transport.send(msg);
            log.info("Sent alert email: {}", subject);
            return true;
        } catch (Exception e) {
            log.warn("Could not send alert email '{}'", subject, e);
            return false;
        }
    }

    private Session buildSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", settings.host());
        props.put("mail.smtp.port", String.valueOf(settings.port()));
        props.put("mail.smtp.auth", notBlank(settings.username()) ? "true" : "false");
        props.put("mail.smtp.starttls.enable", String.valueOf(settings.startTls()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        if (notBlank(settings.username())) {
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(settings.username(), settings.password());
                }
            });
        }
        return Session.getInstance(props);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * SMTP coordinates. For Gmail: {@code host=smtp.gmail.com}, {@code port=587},
     * {@code startTls=true}, {@code username=<you>@gmail.com}, {@code password=<app password>},
     * {@code from=<you>@gmail.com}, {@code to} = one or more comma-separated recipients.
     */
    public record Settings(boolean enabled, String host, int port, boolean startTls,
                           String username, String password, String from, String to) {
    }
}
