package com.athletex.wallet;

import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountRepository;
import com.athletex.ledger.AccountType;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    public record WalletResponse(long cashCents, long escrowCents) {
    }

    private final AccountRepository accountRepository;

    public WalletController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping
    public WalletResponse wallet(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return new WalletResponse(
                balanceOf(userId, AccountType.USER_CASH),
                balanceOf(userId, AccountType.USER_ESCROW));
    }

    private long balanceOf(UUID userId, AccountType type) {
        return accountRepository.findByOwnerUserIdAndType(userId, type)
                .map(AccountEntity::getBalanceCents)
                .orElse(0L);
    }
}
