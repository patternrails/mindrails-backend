package com.mindrails.backend.identity;

import java.util.*;
import com.mindrails.backend.identity.Identity.*;

interface IdentityStore {
    Optional<User> user(UUID id);
    Optional<User> login(String loginId);
    List<User> users();
    void insert(User user);
    void update(User user);
    void delete(UUID id);
    void insert(Session session);
    Optional<Session> access(String hash);
    Optional<Session> refresh(String hash);
    Optional<Session> session(UUID id);
    boolean rotate(String previousRefreshHash, Session session);
    void revoke(UUID sessionId);
    void revokeUser(UUID userId);
    void purge(java.time.Instant now);
}
