package com.athletex.auth;

import com.athletex.auth.AuthDtos.AuthResponse;
import com.athletex.auth.AuthDtos.LoginRequest;
import com.athletex.auth.AuthDtos.RegisterRequest;
import com.athletex.common.ConflictException;
import com.athletex.ledger.AccountEntity;
import com.athletex.ledger.AccountRepository;
import com.athletex.ledger.AccountType;
import com.athletex.ledger.LedgerService;
import com.athletex.ledger.Posting;
import com.athletex.ledger.TransactionType;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final long seedBalanceCents;

    public AuthService(UserRepository userRepository,
                       AccountRepository accountRepository,
                       LedgerService ledgerService,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       @Value("${athletex.seed-balance-cents}") long seedBalanceCents) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.seedBalanceCents = seedBalanceCents;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("username is already taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("email is already registered");
        }
        UserEntity user = userRepository.save(
                new UserEntity(request.username(), request.email(), passwordEncoder.encode(request.password())));
        AccountEntity cash = accountRepository.save(AccountEntity.userAccount(user.getId(), AccountType.USER_CASH));
        accountRepository.save(AccountEntity.userAccount(user.getId(), AccountType.USER_ESCROW));
        ledgerService.post("seed:" + user.getId(), TransactionType.SEED_GRANT, List.of(
                new Posting(AccountEntity.HOUSE_ACCOUNT_ID, -seedBalanceCents),
                new Posting(cash.getId(), seedBalanceCents)));
        return new AuthResponse(tokenService.issue(user), user.getUsername());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.username())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("invalid username or password"));
        return new AuthResponse(tokenService.issue(user), user.getUsername());
    }
}
