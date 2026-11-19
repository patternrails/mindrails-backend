package com.mindrails.backend.identity;

import com.mindrails.backend.identity.Identity.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    public static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.agent.registry.ReadOnlyAgentRegistry resourceAgents;
    private final IdentityStore store;
    private final IdentityProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash = PasswordHash.hash(UUID.randomUUID().toString());
    private final Map<String, Ticket> tickets = new HashMap<>();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private record Ticket(UUID sessionId, String path, Instant expires) {}
    private record Attempt(int count, Instant until) {}

    @org.springframework.beans.factory.annotation.Autowired
    public IdentityService(IdentityStore store, IdentityProperties properties) { this(store, properties, Clock.systemUTC()); }
    IdentityService(IdentityStore store, IdentityProperties properties, Clock clock) {
        this.store = store; this.properties = properties; this.clock = clock;
        if (properties.getAccessSeconds() < 1 || properties.getRefreshSeconds() <= properties.getAccessSeconds())
            throw new IllegalStateException("Identity token lifetimes are invalid");
        if (!Set.of("Strict", "Lax", "None").contains(properties.getCookieSameSite()) ||
                (properties.getCookieSameSite().equals("None") && !properties.isSecureCookie()))
            throw new IllegalStateException("SameSite=None requires secure cookies");
        if (properties.getAdminPassword().isBlank())
            throw new IllegalStateException("Set mindrails.identity.admin-password (MINDRAILS_ADMIN_PASSWORD) before starting MindRails");
        PasswordHash.validate(properties.getAdminPassword());
        String login = loginId(properties.getAdminLoginId());
        User existing = store.user(ADMIN_ID).orElse(null);
        String hash = existing != null && PasswordHash.matches(properties.getAdminPassword(), existing.passwordHash())
                ? existing.passwordHash() : PasswordHash.hash(properties.getAdminPassword());
        if (existing == null) store.insert(new User(ADMIN_ID,login,"Administrator","","","","en",Role.SystemAdmin,hash,0,0,clock.instant()));
        else if (!existing.loginId().equals(login) || !existing.passwordHash().equals(hash) || existing.role() != Role.SystemAdmin) {
            store.update(new User(ADMIN_ID,login,existing.name(),existing.email(),existing.bio(),existing.avatarUrl(),existing.language(),Role.SystemAdmin,
                    hash,existing.version()+1,existing.authVersion()+1,existing.createdAt()));
            store.revokeUser(ADMIN_ID);
        }
    }
    public synchronized Tokens login(String login, String password, String clientAddress) {
        Instant now = clock.instant();
        attempts.values().removeIf(a -> !a.until().isAfter(now));
        // Bound password derivation work by both address and account, with bounded in-memory bookkeeping.
        String account = login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
        for (String key : List.of("ip:"+clientAddress, "login:"+account)) {
            Attempt a = attempts.get(key);
            if (a != null && a.count() >= 10) throw new IdentityException(429,"Too many login attempts; try again in 15 minutes");
        }
        User user = account.length() <= 64 ? store.login(account).orElse(null) : null;
        boolean valid = PasswordHash.matches(password, user == null ? dummyPasswordHash : user.passwordHash());
        if (user == null || !valid) {
            if (attempts.size() > 10_000) throw new IdentityException(429,"Too many login attempts; try again later");
            for (String key : List.of("ip:"+clientAddress, "login:"+account.substring(0,Math.min(account.length(),64))))
                attempts.compute(key,(k,a) -> new Attempt(a == null ? 1 : a.count()+1, a == null ? now.plusSeconds(900) : a.until()));
            throw IdentityException.unauthorized();
        }
        attempts.remove("login:"+account);
        store.purge(now);
        return issue(user, null);
    }
    public synchronized Tokens refresh(String refreshToken) {
        Session old = store.refresh(digestToken(refreshToken)).orElseThrow(IdentityException::unauthorized);
        store.purge(clock.instant());
        return issue(validUser(old), old);
    }
    private Tokens issue(User user, Session old) {
        Instant now = clock.instant();
        String access = randomToken(), refresh = randomToken();
        Session session = new Session(old == null ? UUID.randomUUID() : old.id(),user.id(),user.authVersion(),digest(access),digest(refresh),
                now.plusSeconds(properties.getAccessSeconds()),old == null ? now.plusSeconds(properties.getRefreshSeconds()) : old.refreshExpiresAt());
        if (old == null) store.insert(session);
        else if (!store.rotate(old.refreshHash(),session)) throw IdentityException.unauthorized();
        return new Tokens(access,refresh,session.accessExpiresAt(),session.refreshExpiresAt(),user.profile());
    }
    public Current authenticate(String accessToken) {
        Session s = store.access(digestToken(accessToken)).orElseThrow(IdentityException::unauthorized);
        if (!s.accessExpiresAt().isAfter(clock.instant())) throw IdentityException.unauthorized();
        return current(s,validUser(s));
    }
    public Current sessionIdentity(UUID id) {
        Session s = store.session(id).orElseThrow(IdentityException::unauthorized);
        return current(s,validUser(s));
    }
    private User validUser(Session s) {
        if (!s.refreshExpiresAt().isAfter(clock.instant())) throw IdentityException.unauthorized();
        User user = store.user(s.userId()).orElseThrow(IdentityException::unauthorized);
        if (user.authVersion() != s.userVersion()) throw IdentityException.unauthorized();
        return user;
    }
    private Current current(Session s,User u) { return new Current(u.id(),u.name(),u.role(),s.id()); }
    public Profile profile(UUID id) { return user(id).profile(); }
    private User user(UUID id) { return store.user(id).orElseThrow(() -> new IdentityException(404,"User not found")); }
    /** Minimal authenticated sharing directory, distinct from administrative account management. */
    public record ShareCandidate(UUID id, String name, String loginId) {}
    public List<ShareCandidate> shareCandidates(String query) {
        if(ResourceAccess.userId()==null) throw IdentityException.unauthorized();
        String q=Objects.toString(query, "").strip().toLowerCase(Locale.ROOT);
        return store.users().stream().filter(u -> q.isEmpty() || u.name().toLowerCase(Locale.ROOT).contains(q) || u.loginId().toLowerCase(Locale.ROOT).contains(q))
                .sorted(java.util.Comparator.comparing(User::name).thenComparing(User::id)).limit(100)
                .map(u->new ShareCandidate(u.id(),u.name(),u.loginId())).toList();
    }
    public List<Profile> users(Current actor) { requireAdmin(actor); return store.users().stream().map(User::profile).toList(); }
    public synchronized Profile create(Current actor, UserController.UserWrite input) {
        requireAdmin(actor); validate(input); PasswordHash.validate(input.password());
        User user = new User(UUID.randomUUID(),loginId(input.loginId()),input.name().trim(),clean(input.email()),clean(input.bio()),"",language(input.language(), "en"),
                input.role() == null ? Role.Normal : input.role(),PasswordHash.hash(input.password()),0,0,clock.instant());
        store.insert(user); return user.profile();
    }
    public synchronized Profile update(Current actor, UUID id, UserController.UserWrite input) {
        requireEdit(actor,id); validate(input);
        User old = user(id);
        Role role = input.role() == null ? old.role() : input.role();
        if (!actor.admin() && role != old.role()) throw IdentityException.forbidden();
        String login = loginId(input.loginId());
        boolean passwordChange = input.password() != null && !input.password().isEmpty();
        if (id.equals(ADMIN_ID) && (!old.loginId().equals(login) || role != Role.SystemAdmin || passwordChange))
            throw new IdentityException(400,"Configured administrator credentials and role are managed by application properties");
        if (passwordChange) {
            PasswordHash.validate(input.password());
            if (actor.id().equals(id) && !PasswordHash.matches(input.currentPassword(),old.passwordHash()))
                throw new IdentityException(400,"Current password is incorrect");
        }
        boolean revoke = passwordChange || role != old.role() || !login.equals(old.loginId());
        User updated = new User(id,login,input.name().trim(),clean(input.email()),clean(input.bio()),old.avatarUrl(),language(input.language(), old.language()),role,
                passwordChange ? PasswordHash.hash(input.password()) : old.passwordHash(),old.version()+1,old.authVersion()+(revoke?1:0),old.createdAt());
        store.update(updated); if (revoke) store.revokeUser(id);
        return updated.profile();
    }
    public synchronized Profile avatar(Current actor, UUID id, String url) {
        requireEdit(actor,id); User old=user(id);
        User updated=new User(id,old.loginId(),old.name(),old.email(),old.bio(),url,old.language(),old.role(),old.passwordHash(),old.version()+1,old.authVersion(),old.createdAt());
        store.update(updated); return updated.profile();
    }
    public synchronized Profile language(Current actor, String language) {
        User old = user(actor.id());
        String selected = language(language, old.language());
        if (selected.equals(old.language())) return old.profile();
        User updated = new User(old.id(),old.loginId(),old.name(),old.email(),old.bio(),old.avatarUrl(),
                selected,old.role(),old.passwordHash(),old.version()+1,old.authVersion(),old.createdAt());
        store.update(updated);
        return updated.profile();
    }
    private static String language(String value, String fallback) {
        String selected = value == null ? fallback : value;
        if (!Set.of("en", "zh").contains(selected)) throw new IdentityException(400,"Language must be en or zh");
        return selected;
    }
    public synchronized void delete(Current actor,UUID id) {
        requireAdmin(actor);
        if (ADMIN_ID.equals(id) || actor.id().equals(id)) throw new IdentityException(400,"You cannot delete this administrator");
        user(id); store.delete(id);
    }
    public void logout(Current actor) { store.revoke(actor.sessionId()); }
    public void logoutRefresh(String token) { if(token != null && !token.isBlank()) store.refresh(digestToken(token)).ifPresent(s -> store.revoke(s.id())); }
    public synchronized String ticket(Current actor,String path) {
        boolean userStatus = "/api/user/status/stream".equals(path);
        if (path == null || !userStatus && !path.matches("/api/agent/[0-9a-fA-F-]{36}/(sessions|tasks)/[0-9a-fA-F-]{36}/stream"))
            throw new IdentityException(400,"Invalid WebSocket path");
        if(resourceAgents!=null && !userStatus) {
            var parts=path.split("/");
            try(var scope=ResourceAccess.as(actor.id())) {
                var agent=resourceAgents.find(UUID.fromString(parts[3])).orElseThrow(() -> new IdentityException(404,"Resource not found"));
                var resource=UUID.fromString(parts[5]);
                if (!(parts[4].equals("sessions") ? agent.sessionIds() : agent.taskIds()).contains(resource))
                    throw new IdentityException(404,"Resource not found");
            }
        }
        tickets.values().removeIf(t -> !t.expires().isAfter(clock.instant()));
        if (tickets.size() >= 10_000) throw new IdentityException(429,"Too many pending WebSocket connections");
        String token=randomToken(); tickets.put(digest(token),new Ticket(actor.sessionId(),path,clock.instant().plusSeconds(30))); return token;
    }
    public synchronized Current consumeTicket(String token,String path) {
        Ticket t=tickets.remove(digestToken(token));
        if(t == null || !t.path().equals(path) || !t.expires().isAfter(clock.instant())) throw IdentityException.unauthorized();
        return sessionIdentity(t.sessionId());
    }
    public static void requireAdmin(Current actor) { if(actor == null || !actor.admin()) throw IdentityException.forbidden(); }
    static void requireEdit(Current actor,UUID id) { if(actor == null || (!actor.admin() && !actor.id().equals(id))) throw IdentityException.forbidden(); }
    private static String loginId(String id) {
        if(id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) throw new IdentityException(400,"Login ID must be 3–64 letters, digits, dots, underscores or hyphens");
        return id.toLowerCase(Locale.ROOT);
    }
    private static void validate(UserController.UserWrite i) {
        loginId(i.loginId());
        if (i.name() == null || i.name().isBlank() || i.name().length()>100 || clean(i.email()).length()>254 || clean(i.bio()).length()>2000 ||
                (!clean(i.email()).isEmpty() && !clean(i.email()).matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) ||
                (i.name()+clean(i.email())).chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IdentityException(400,"Check name (1–100), email (254) and bio (2000) limits");
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static final SecureRandom RANDOM=new SecureRandom();
    private static String randomToken() { byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String digestToken(String token) {
        if(token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw IdentityException.unauthorized();
        return digest(token);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
