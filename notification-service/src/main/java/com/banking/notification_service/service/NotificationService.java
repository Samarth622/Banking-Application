package com.banking.notification_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class NotificationService {


    @KafkaListener(topics = "transaction.otp.generated")
    public void consumeOTPGenerated(
            @Payload Map<String, Object> payload) {

        try {

            String accountNumber = (String) payload.get("accountNumber");
            String otp = (String) payload.get("otp");
            String transactionId = (String) payload.get("transactionId");
            String reason =  (String) payload.get("reason");
            String amount = (String) payload.get("amount");

            sendAlert(
                    accountNumber,
                    "TRANSACTION VERIFICATION REQUIRED",
                    String.format(
                            "Suspicious activity detected on your account." +
                            "Reason: %s." +
                            "A transaction of %s is pending verification." +
                            "Your OTP is %s and valid for 5 minutes." +
                            "If this wasn't you - ignore this message.", reason, amount, otp
                    )
            );

        } catch (Exception e) {
            log.error("Error sending OTP notification: {}", e.getMessage());
        }
    }

    private void sendAlert(String accountNumber, String subject, String message) {

    }
}
