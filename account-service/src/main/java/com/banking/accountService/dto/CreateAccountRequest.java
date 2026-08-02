package com.banking.accountService.dto;

import com.banking.accountService.entity.AccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateAccountRequest {

    @NotBlank(message = "Account Holder Name is required")
    private String accountHolderName;

    @NotBlank(message = "Email is required")
    @Email(message = "Wrong email format")
    private String email;

    @NotBlank(message = "Phone number is required")
    private String phone;

    @NotNull(message = "Account Type is required")
    private AccountType accountType;

    @NotNull(message = "Initial Deposit is required")
    @Positive(message = "Initial Deposit must be positive")
    private BigDecimal initialDeposit;
}
