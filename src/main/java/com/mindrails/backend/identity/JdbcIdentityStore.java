package com.mindrails.backend.identity;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.mindrails.backend.identity.Identity.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;

final class JdbcIdentityStore implements IdentityStore {
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    JdbcIdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }
    private User readUser(ResultSet r, int row) throws SQLException {
        return new User(UUID.fromString(r.getString("id")), r.getString("login_id"), r.getString("name"), r.getString("email"),
                r.getString("bio"), r.getString("avatar_url"), r.getString("language"), Role.valueOf(r.getString("role")), r.getString("password_hash"),
                r.getLong("version"), r.getLong("auth_version"), Instant.ofEpochMilli(r.getLong("created_at")));
    }
    private Session readSession(ResultSet r, int row) throws SQLException {
        return new Session(UUID.fromString(r.getString("id")), UUID.fromString(r.getString("user_id")), r.getLong("user_version"),
                r.getString("access_hash"), r.getString("refresh_hash"), Instant.ofEpochMilli(r.getLong("access_expires_at")),
                Instant.ofEpochMilli(r.getLong("refresh_expires_at")));
    }
    public Optional<User> user(UUID id) { return jdbc.query("SELECT * FROM identity_users WHERE id=?", this::readUser, id.toString()).stream().findFirst(); }
    public Optional<User> login(String id) { return jdbc.query("SELECT * FROM identity_users WHERE login_id=?", this::readUser, id).stream().findFirst(); }
    public List<User> users() { return jdbc.query("SELECT * FROM identity_users ORDER BY created_at,id", this::readUser); }
    public void insert(User u) {
        try { jdbc.update("INSERT INTO identity_users (id,login_id,name,email,bio,avatar_url,language,role,password_hash,version,auth_version,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                u.id().toString(),u.loginId(),u.name(),u.email(),u.bio(),u.avatarUrl(),u.language(),u.role().name(),u.passwordHash(),u.version(),u.authVersion(),u.createdAt().toEpochMilli()); }
        catch (DuplicateKeyException e) { throw new IdentityException(409, "Login ID already exists"); }
    }
    public void update(User u) {
        try {
            if (jdbc.update("UPDATE identity_users SET login_id=?,name=?,email=?,bio=?,avatar_url=?,language=?,role=?,password_hash=?,version=?,auth_version=? WHERE id=? AND version=?",
                    u.loginId(),u.name(),u.email(),u.bio(),u.avatarUrl(),u.language(),u.role().name(),u.passwordHash(),u.version(),u.authVersion(),u.id().toString(),u.version()-1) != 1)
                throw new IdentityException(409, "User changed; reload and try again");
        } catch (DuplicateKeyException e) { throw new IdentityException(409, "Login ID already exists"); }
    }
    public void delete(UUID id) { jdbc.update("DELETE FROM identity_users WHERE id=?", id.toString()); }
    public void insert(Session s) {
        transactions.executeWithoutResult(status -> {
        jdbc.update("INSERT INTO identity_sessions (id,user_id,user_version,access_hash,refresh_hash,access_expires_at,refresh_expires_at) VALUES (?,?,?,?,?,?,?)",
                s.id().toString(),s.userId().toString(),s.userVersion(),s.accessHash(),s.refreshHash(),s.accessExpiresAt().toEpochMilli(),s.refreshExpiresAt().toEpochMilli());
        insertAccess(s);
        });
    }
    public Optional<Session> access(String h) {
        return jdbc.query("SELECT s.*, a.expires_at AS token_expires_at FROM identity_access_tokens a JOIN identity_sessions s ON s.id=a.session_id WHERE a.token_hash=?",
                (r,row) -> {Session s=readSession(r,row);return new Session(s.id(),s.userId(),s.userVersion(),h,s.refreshHash(),Instant.ofEpochMilli(r.getLong("token_expires_at")),s.refreshExpiresAt());},h).stream().findFirst();
    }
    private void insertAccess(Session s) {
        jdbc.update("INSERT INTO identity_access_tokens (token_hash,session_id,expires_at) VALUES (?,?,?)",s.accessHash(),s.id().toString(),s.accessExpiresAt().toEpochMilli());
    }
    public Optional<Session> refresh(String h) { return jdbc.query("SELECT * FROM identity_sessions WHERE refresh_hash=?", this::readSession,h).stream().findFirst(); }
    public Optional<Session> session(UUID id) { return jdbc.query("SELECT * FROM identity_sessions WHERE id=?", this::readSession,id.toString()).stream().findFirst(); }
    public boolean rotate(String old, Session s) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (jdbc.update("UPDATE identity_sessions SET access_hash=?,refresh_hash=?,access_expires_at=? WHERE id=? AND refresh_hash=?",
                    s.accessHash(),s.refreshHash(),s.accessExpiresAt().toEpochMilli(),s.id().toString(),old) != 1) return false;
            insertAccess(s);return true;
        }));
    }
    public void revoke(UUID id) { jdbc.update("DELETE FROM identity_sessions WHERE id=?",id.toString()); }
    public void revokeUser(UUID id) { jdbc.update("DELETE FROM identity_sessions WHERE user_id=?",id.toString()); }
    public void purge(Instant now) { jdbc.update("DELETE FROM identity_access_tokens WHERE expires_at<=?",now.toEpochMilli()); jdbc.update("DELETE FROM identity_sessions WHERE refresh_expires_at<=?", now.toEpochMilli()); }
}
