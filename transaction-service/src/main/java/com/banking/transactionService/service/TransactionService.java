package com.banking.transactionService.service;

import com.banking.transactionService.client.AccountServiceClient;
import com.banking.transactionService.dto.TransactionResponse;
import com.banking.transactionService.dto.TransferRequest;
import com.banking.transactionService.entity.Transaction;
import com.banking.transactionService.entity.TransactionStatus;
import com.banking.transactionService.entity.TransactionType;
import com.banking.transactionService.event.TransactionInitiatedEvent;
import com.banking.transactionService.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String , String> redisTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC = "transaction.refunded";

    /**
     * SAGA Step 1 - Initiate transfer
     * Deducts from sender by feign
     * Saves transaction as PROCESSING
     * Publish event for Kafka for fraud check
     * Returns
     * @param request
     * @return
     */
    public TransactionResponse transfer(TransferRequest request) {
        log.info("SAGA Start - Transfer: {} -> {}, amount: {}",
                request.getSenderAccountNumber(),
                request.getReceiverAccountNumber(),
                request.getAmount());

        accountServiceClient.deductBalance(
                request.getSenderAccountNumber(),
                request.getAmount()
        );

        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction saved as PROCESSING: {}", savedTransaction.getId());

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event);
        log.info("TransactionInitiatedEvent saved as PROCESSING: {}", savedTransaction.getId());

        return mapToResponse(savedTransaction);
    }

    public TransactionResponse getTransaction(String transactionId) {
        return mapToResponse(
                transactionRepository.findById(transactionId)
                        .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + transactionId))
        );
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber) {
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public TransactionResponse verifyOTP(String transactionID, String otp) {
            log.info("OTP verification for the transaction: {}", transactionID);

            Transaction transaction = transactionRepository.findById(transactionID)
                    .orElseThrow(
                            () -> new RuntimeException("Transaction not found with id: " + transactionID)
                    );

        String otpKey = "verification:otp" + transactionID;
        String storeOTP = redisTemplate.opsForValue().get(otpKey);

        // when OTP expired
        if(storeOTP == null) {
            log.warn("OTP expired for transaction: {}", transactionID);
            compensateTransaction(transaction, "OTP expired - transaction cancelled and amount refunded");
            return mapToResponse(transaction);
        }

        // enter wrong otp
        if(!storeOTP.equals(otp)) {
            log.warn("Wrong OTP - blocking account and refunding: {}", transactionID);
            redisTemplate.delete(otpKey);
            blockingAccountAndCompensate(transaction,
                    "Wrong OTP entered - transaction cancelled, " +
                    "account blocking for security");
            return mapToResponse(transaction);
        }

        // correct OTP - complete transaction
        log.info("OTP verified - completing transaction: {}", transactionID);
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);
    }

    public void compensateTransaction(Transaction transaction, String reason) {
        log.warn("SAGA COMPENSATION - refunding: {} amount: {}",
                transaction.getSenderAccountNumber(), transaction.getAmount());

        // CREDIT MONEY BACK TO SENDERS SYNCHRONOUSLY
        accountServiceClient.creditBalance(
                transaction.getSenderAccountNumber(),
                transaction.getAmount()
        );

        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setFailureReason(reason +
                " - SAGA compensation executed, amount refunded at " + LocalDateTime.now());

        transactionRepository.save(transaction);

        // PUBLISH refund event - Notification service will alert user
        Map<String, Object> refundEvent = new HashMap<>();
        refundEvent.put("transactionId", transaction.getId());
        refundEvent.put("senderAccountNumber", transaction.getSenderAccountNumber());
        refundEvent.put("amount", transaction.getAmount());
        refundEvent.put("reason", reason);

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId(), refundEvent);

        log.info("SAGA compensation completed: {}, refunded to {}",
                transaction.getId(), transaction.getSenderAccountNumber());
    }

    private TransactionResponse mapToResponse(Transaction transaction) {
        TransactionResponse transactionResponse = new TransactionResponse();
        transactionResponse.setId(transaction.getId());
        transactionResponse.setSenderAccountNumber(transaction.getSenderAccountNumber());
        transactionResponse.setReceiverAccountNumber(transaction.getReceiverAccountNumber());
        transactionResponse.setAmount(transaction.getAmount());
        transactionResponse.setStatus(transaction.getStatus());
        transactionResponse.setType(transaction.getType());
        transactionResponse.setDescription(transaction.getDescription());
        transactionResponse.setReferenceNumber(transaction.getReferenceNumber());
        transactionResponse.setCreatedAt(transaction.getCreatedAt());
        transactionResponse.setCompletedAt(transaction.getCompletedAt());
        transactionResponse.setFailureReason(transaction.getFailureReason());
        return transactionResponse;
    }
}
