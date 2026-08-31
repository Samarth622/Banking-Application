package com.banking.notification_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class NotificationService {


    public void consumeOTPGenerated(
            @Payload Map<String, Object> payload) {


    }
}
