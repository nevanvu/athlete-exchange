package com.athletex.auth;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Exchange operators, by username (athletex.admin-usernames). Their tokens carry the "admin" scope. */
@Component
public class AdminDirectory {

    private final Set<String> admins;

    public AdminDirectory(@Value("${athletex.admin-usernames:}") String adminUsernames) {
        this.admins = Arrays.stream(adminUsernames.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String username) {
        return admins.contains(username);
    }
}
