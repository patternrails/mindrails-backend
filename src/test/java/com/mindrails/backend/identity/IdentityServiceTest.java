package com.mindrails.backend.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.identity.Identity.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class IdentityServiceTest {
    static final String PASSWORD="correct-horse-battery-staple";
    InMemoryIdentityStore store;
    IdentityProperties properties;
    IdentityService service;
    MutableClock clock;
    Current admin;
    @BeforeEach void setup() {
        store=new InMemoryIdentityStore();properties=new IdentityProperties();properties.setAdminPassword(PASSWORD);
        clock=new MutableClock();service=new IdentityService(store,properties,clock);
        admin=service.authenticate(service.login("admin",PASSWORD,"local").accessToken());
    }
    UserController.UserWrite input(String login,Role role,String password,String current) {
        return new UserController.UserWrite(login,"Test User","test@example.com","Bio",password,current,role);
    }
    Profile create() { return service.create(admin,input("alice",Role.Normal,PASSWORD,null)); }
    Current alice() { create();return service.authenticate(service.login("ALICE",PASSWORD,"local").accessToken()); }
    @Test void languageIsAnAccountPreferenceAndDoesNotInvalidateLoginSessions() {
        Current actor = alice();
        var login = service.login("alice", PASSWORD, "second");
        assertEquals("en", service.profile(actor.id()).language());
        assertEquals("zh", service.language(actor, "zh").language());
        assertEquals(actor.id(), service.sessionIdentity(actor.sessionId()).id());
        assertEquals("zh", service.refresh(login.refreshToken()).user().language());
        assertThrows(IdentityException.class, () -> service.language(actor, "fr"));
        assertEquals("zh", service.profile(actor.id()).language());
        assertEquals("en", service.profile(admin.id()).language());
    }
    @Test void passwordHashesHaveIndependentSaltsAndNeverContainThePassword() {
        String a=PasswordHash.hash(PASSWORD), b=PasswordHash.hash(PASSWORD);
        assertNotEquals(a,b);assertFalse(a.contains(PASSWORD));assertTrue(PasswordHash.matches(PASSWORD,a));assertFalse(PasswordHash.matches("bad",a));
    }
    @Test void accessExpiresButRefreshRotatesUntilItsAbsoluteDeadline() {
        create();Tokens first=service.login("alice",PASSWORD,"local");
        assertEquals("Test User",service.authenticate(first.accessToken()).name());
        clock.advance(properties.getAccessSeconds());assertThrows(IdentityException.class,() -> service.authenticate(first.accessToken()));
        Tokens second=service.refresh(first.refreshToken());
        assertEquals(first.refreshExpiresAt(),second.refreshExpiresAt());assertNotEquals(first.refreshToken(),second.refreshToken());
        assertThrows(IdentityException.class,() -> service.refresh(first.refreshToken()));
        assertEquals(Role.Normal,service.authenticate(second.accessToken()).role());
        clock.advance(properties.getRefreshSeconds());assertThrows(IdentityException.class,() -> service.refresh(second.refreshToken()));
    }
    @Test void normalUserCanReadDetailsAndEditSelfButCannotManageOthersOrGrantRoles() {
        Current alice=alice();
        assertEquals("admin",service.profile(admin.id()).loginId());
        assertEquals(403,assertThrows(IdentityException.class,() -> service.users(alice)).status);
        assertThrows(IdentityException.class,() -> service.create(alice,input("bob",Role.Normal,PASSWORD,null)));
        assertThrows(IdentityException.class,() -> service.delete(alice,admin.id()));
        assertThrows(IdentityException.class,() -> service.update(alice,admin.id(),input("admin",Role.SystemAdmin,null,null)));
        assertThrows(IdentityException.class,() -> service.update(alice,alice.id(),input("alice",Role.SystemAdmin,null,null)));
        service.update(alice,alice.id(),input("alice",Role.Normal,null,null));
        assertEquals(alice.id(),service.sessionIdentity(alice.sessionId()).id());
    }
    @Test void passwordChangesRequireCurrentPasswordForSelfAndRevokeEverySession() {
        Current alice=alice();Tokens other=service.login("alice",PASSWORD,"other");
        assertThrows(IdentityException.class,() -> service.update(alice,alice.id(),input("alice",Role.Normal,"different-password","wrong")));
        service.update(alice,alice.id(),input("alice",Role.Normal,"different-password",PASSWORD));
        assertThrows(IdentityException.class,() -> service.sessionIdentity(alice.sessionId()));
        assertThrows(IdentityException.class,() -> service.refresh(other.refreshToken()));
        assertThrows(IdentityException.class,() -> service.login("alice",PASSWORD,"local"));
        assertNotNull(service.login("alice","different-password","local"));
    }
    @Test void administratorCanResetPasswordsAndDeletionRevokesAccessAndRefresh() {
        Current alice=alice();service.update(admin,alice.id(),input("alice",Role.Normal,"reset-password-2026",null));
        assertThrows(IdentityException.class,() -> service.sessionIdentity(alice.sessionId()));
        Tokens tokens=service.login("alice","reset-password-2026","local");service.delete(admin,alice.id());
        assertThrows(IdentityException.class,() -> service.authenticate(tokens.accessToken()));
        assertThrows(IdentityException.class,() -> service.refresh(tokens.refreshToken()));
    }
    @Test void configuredAdministratorCannotBeDeletedOrModifiedThroughUserCredentials() {
        assertThrows(IdentityException.class,() -> service.delete(admin,admin.id()));
        assertThrows(IdentityException.class,() -> service.update(admin,admin.id(),input("renamed",Role.SystemAdmin,null,null)));
        assertThrows(IdentityException.class,() -> service.update(admin,admin.id(),input("admin",Role.Normal,null,null)));
        assertThrows(IdentityException.class,() -> service.update(admin,admin.id(),input("admin",Role.SystemAdmin,"another-password",PASSWORD)));
        service.update(admin,admin.id(),input("admin",Role.SystemAdmin,null,null));
        assertTrue(service.profile(admin.id()).configuredAdmin());
        properties.setAdminPassword("replacement-config-password");
        IdentityService restarted=new IdentityService(store,properties,clock);
        assertThrows(IdentityException.class,() -> restarted.sessionIdentity(admin.sessionId()));
        assertNotNull(restarted.login("admin","replacement-config-password","local"));
    }
    @Test void roleChangesRevokeOldAuthorityAndNewLoginGetsCurrentRole() {
        Current alice=alice();service.update(admin,alice.id(),input("alice",Role.SystemAdmin,null,null));
        assertThrows(IdentityException.class,() -> service.sessionIdentity(alice.sessionId()));
        Current promoted=service.authenticate(service.login("alice",PASSWORD,"local").accessToken());
        assertTrue(promoted.admin());assertEquals(2,service.users(promoted).size());
    }
    @Test void ticketsAreShortLivedOneUseBoundToPathAndRevokedWithTheSession() {
        String path="/api/agent/"+UUID.randomUUID()+"/sessions/"+UUID.randomUUID()+"/stream";
        String wrongPath=service.ticket(admin,path);
        assertThrows(IdentityException.class,() -> service.consumeTicket(wrongPath,path+"x"));
        String ticket=service.ticket(admin,path);assertEquals(admin.id(),service.consumeTicket(ticket,path).id());
        String used=ticket;assertThrows(IdentityException.class,() -> service.consumeTicket(used,path));
        String expired=service.ticket(admin,path);clock.advance(30);assertThrows(IdentityException.class,() -> service.consumeTicket(expired,path));
        String revoked=service.ticket(admin,path);service.logout(admin);assertThrows(IdentityException.class,() -> service.consumeTicket(revoked,path));
    }
    @Test void userStatusTicketIsFixedPathAndBoundToAuthenticatedUser() {
        Current actor = alice(); String path = "/api/user/status/stream";
        String ticket = service.ticket(actor, path);
        assertEquals(actor.id(), service.consumeTicket(ticket, path).id());
        assertThrows(IdentityException.class, () -> service.consumeTicket(ticket, path));
        assertThrows(IdentityException.class, () -> service.ticket(actor, path + "/"));
        assertThrows(IdentityException.class, () -> service.ticket(actor, "/api/user/" + admin.id() + "/status/stream"));
    }
    @Test void fieldLimitsAndCaseInsensitiveLoginUniquenessAreEnforced() {
        create();assertEquals(409,assertThrows(IdentityException.class,() -> service.create(admin,input("ALICE",Role.Normal,PASSWORD,null))).status);
        assertThrows(IdentityException.class,() -> service.create(admin,input("../bad",Role.Normal,PASSWORD,null)));
        assertThrows(IdentityException.class,() -> service.create(admin,input("bob",Role.Normal,"short",null)));
        assertThrows(IdentityException.class,() -> service.create(admin,new UserController.UserWrite("bob"," ","","",PASSWORD,null,Role.Normal)));
        assertThrows(IdentityException.class,() -> service.create(admin,new UserController.UserWrite("bob","Bob","invalid","",PASSWORD,null,Role.Normal)));
    }
    @Test void repeatedFailedLoginsAreThrottledAndCooldownExpires() {
        for(int i=0;i<10;i++) assertEquals(401,assertThrows(IdentityException.class,() -> service.login("admin","bad","remote")).status);
        assertEquals(429,assertThrows(IdentityException.class,() -> service.login("admin",PASSWORD,"remote")).status);
        clock.advance(901);assertNotNull(service.login("admin",PASSWORD,"remote"));
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-23T00:00:00Z");
        void advance(long seconds) {now=now.plusSeconds(seconds);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
}
