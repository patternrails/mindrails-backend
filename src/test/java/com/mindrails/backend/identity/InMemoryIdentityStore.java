package com.mindrails.backend.identity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.mindrails.backend.identity.Identity.*;

final class InMemoryIdentityStore implements IdentityStore {
    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private record Access(UUID sessionId,java.time.Instant expires) {}
    private final Map<String,Access> accessTokens=new ConcurrentHashMap<>();
    public Optional<User> user(UUID id) { return Optional.ofNullable(users.get(id)); }
    public Optional<User> login(String loginId) { return users.values().stream().filter(u -> u.loginId().equalsIgnoreCase(loginId)).findFirst(); }
    public List<User> users() { return List.copyOf(users.values()); }
    public synchronized void insert(User u) {
        if (login(u.loginId()).isPresent() || users.putIfAbsent(u.id(), u) != null) throw new IdentityException(409, "Login ID already exists");
    }
    public synchronized void update(User u) {
        if (login(u.loginId()).filter(x -> !x.id().equals(u.id())).isPresent()) throw new IdentityException(409, "Login ID already exists");
        User old = users.get(u.id());
        if (old == null || old.version() != u.version() - 1) throw new IdentityException(409, "User changed; reload and try again");
        users.put(u.id(), u);
    }
    public void delete(UUID id) { users.remove(id); revokeUser(id); }
    public void insert(Session s) { sessions.put(s.id(), s); accessTokens.put(s.accessHash(),new Access(s.id(),s.accessExpiresAt())); }
    public Optional<Session> access(String hash) {
        Access access=accessTokens.get(hash);if(access==null)return Optional.empty();
        return session(access.sessionId()).map(s -> new Session(s.id(),s.userId(),s.userVersion(),hash,s.refreshHash(),access.expires(),s.refreshExpiresAt()));
    }
    public Optional<Session> refresh(String hash) { return sessions.values().stream().filter(s -> s.refreshHash().equals(hash)).findFirst(); }
    public Optional<Session> session(UUID id) { return Optional.ofNullable(sessions.get(id)); }
    public synchronized boolean rotate(String old, Session s) {
        Session previous = sessions.get(s.id());
        if (previous == null || !previous.refreshHash().equals(old)) return false;
        sessions.put(s.id(), s); accessTokens.put(s.accessHash(),new Access(s.id(),s.accessExpiresAt())); return true;
    }
    public void revoke(UUID id) { sessions.remove(id); }
    public void revokeUser(UUID id) { sessions.values().removeIf(s -> s.userId().equals(id)); }
    public void purge(java.time.Instant now) { sessions.values().removeIf(s -> !s.refreshExpiresAt().isAfter(now)); accessTokens.values().removeIf(a -> !a.expires().isAfter(now) || !sessions.containsKey(a.sessionId())); }
}
