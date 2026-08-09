package com.banking.accountService.service;

import com.banking.accountService.dto.AccountResponse;
import com.banking.accountService.dto.CreateAccountRequest;
import com.banking.accountService.entity.Account;
import com.banking.accountService.entity.AccountStatus;
import com.banking.accountService.entity.AccountType;
import com.banking.accountService.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    private static final SecureRandom secureRandom = new SecureRandom();

    public AccountResponse createAccount(CreateAccountRequest request) {
        log.info("Creating account for email: {}", request.getEmail());

        if(accountRepository.existsByEmail(request.getEmail())){
            throw new RuntimeException("Account already exists");
        }

        Account account = Account.builder()
                .email(request.getEmail())
                .phone(request.getPhone())
                .accountHolderName(request.getAccountHolderName())
                .accountType(request.getAccountType())
                .balance(request.getInitialDeposit())
                .status(AccountStatus.ACTIVE)
                .accountNumber(generateAccountNumber())
                .dailyTransactionLimit(
                        request.getAccountType() == AccountType.SAVINGS
                        ? new BigDecimal("100000")
                        : new BigDecimal("500000")
                )
                .build();

        Account savedAccount = accountRepository.save(account);
        log.info("Account created: {}", savedAccount.getAccountNumber());
        return mapToResponse(savedAccount);
    }

    public AccountResponse getAccount(String accountNumber) {
        log.info("Getting account for account number: {}", accountNumber);

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        log.info("Account found: {}", account.getAccountNumber());

        return  mapToResponse(account);
    }

    public BigDecimal getBalance(String accountNumber) {
        log.info("Getting balance for account number: {}", accountNumber);

        Account account =  accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        return  account.getBalance();
    }

    /*
      * block account - called by fraud detection service by kafka
    */
    public void blockAccount(String accountNumber) {
        log.info("Blocking account for account number: {}", accountNumber);

        Account account =  accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        account.setStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);

        log.info("Account blocked for account number: {}", accountNumber);
    }

    /*
      * deduct amount from sender account
      * used by transaction service
    */
    public void deductBalance(String accountNumber, BigDecimal amount) {
        log.info("Deducting balance for account number: {}", accountNumber);

        Account account =  accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if(account.getStatus() != AccountStatus.ACTIVE){
            throw new RuntimeException("Account not active");
        }

        if(account.getBalance().compareTo(amount) < 0){
            throw new RuntimeException("Insufficient balance");
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        log.info("Amount deducted. New balance: {}", account.getBalance());
    }

    /*
      * credir balance
      * called by transaction service via kafka
    */
    public void creditBalance(String accountNumber, BigDecimal amount) {
        log.info("Credit balance for account number: {}", accountNumber);

        Account account =  accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        log.info("Amount credited. New balance: {}", account.getBalance());
    }

    private String generateAccountNumber(){
        String accountNumber;

        do {

            Long number = secureRandom.nextLong(1_000_000_000_000L);
            accountNumber = String.format("%012d", number);

        } while(accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    private AccountResponse mapToResponse(Account account){
        AccountResponse accountResponse = new AccountResponse();
        accountResponse.setAccountNumber(account.getAccountNumber());
        accountResponse.setBalance(account.getBalance());
        accountResponse.setAccountHolderName(account.getAccountHolderName());
        accountResponse.setAccountType(account.getAccountType());
        accountResponse.setEmail(account.getEmail());
        accountResponse.setPhone(account.getPhone());
        accountResponse.setDailyTransactionLimit(account.getDailyTransactionLimit());
        accountResponse.setId(account.getId());
        accountResponse.setCreatedAt(account.getCreatedAt());
        accountResponse.setStatus(account.getStatus());
        return accountResponse;
    }
}
