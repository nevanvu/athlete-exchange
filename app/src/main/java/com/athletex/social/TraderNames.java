package com.athletex.social;

import com.athletex.auth.UserEntity;
import com.athletex.auth.UserRepository;
import com.athletex.trading.PositionEntity;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** How a trader is named in public: their username only if they made their portfolio public. */
@Component
public class TraderNames {

    public static final String ANONYMOUS = "Anonymous trader";
    public static final String HOUSE = "The house";

    private final UserRepository userRepository;

    public TraderNames(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String publicName(UUID userId) {
        if (PositionEntity.HOUSE_USER_ID.equals(userId)) {
            return HOUSE;
        }
        return userRepository.findById(userId)
                .filter(UserEntity::isPortfolioPublic)
                .map(UserEntity::getUsername)
                .orElse(ANONYMOUS);
    }
}
