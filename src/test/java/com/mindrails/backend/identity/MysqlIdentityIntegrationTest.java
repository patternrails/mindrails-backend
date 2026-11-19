package com.mindrails.backend.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.identity.Identity.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real persistence, refresh compare-and-swap, and cascading token revocation. */
class MysqlIdentityIntegrationTest {
    @Test void usersAndTokensSurviveNewStoreInstancesAndRefreshIsAtomic() throws Exception {
        try(var source=new HikariDataSource()) {
            source.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL","jdbc:mysql://127.0.0.1:3306/mindrails"));
            source.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME","mindrails_app"));
            source.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
            var jdbc=new JdbcTemplate(source);var store=new JdbcIdentityStore(jdbc);var reloaded=new JdbcIdentityStore(jdbc);
            UUID id=UUID.randomUUID(), sessionId=UUID.randomUUID();Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            User user=new User(id,"it-"+id.toString().substring(0,12),"Integration User","it@example.com","","","en",Role.Normal,PasswordHash.hash("integration-password"),0,0,now);
            store.insert(user);
            try {
                assertEquals(user,reloaded.user(id).orElseThrow());
                assertEquals(id,reloaded.login(user.loginId().toUpperCase(Locale.ROOT)).orElseThrow().id());
                User changed=new User(id,user.loginId(),"Updated",user.email(),"Updated bio","","zh",Role.SystemAdmin,user.passwordHash(),1,1,now);
                store.update(changed);assertEquals(changed,reloaded.user(id).orElseThrow());
                assertThrows(IdentityException.class,() -> reloaded.update(changed));
                Session session=new Session(sessionId,id,1,"a".repeat(64),"b".repeat(64),now.plusSeconds(900),now.plusSeconds(3600));
                store.insert(session);assertEquals(session,reloaded.access(session.accessHash()).orElseThrow());
                CountDownLatch start=new CountDownLatch(1);
                try(var executor=Executors.newFixedThreadPool(2)) {
                    var futures=new ArrayList<Future<Boolean>>();
                    for(String prefix:List.of("c","d")) futures.add(executor.submit(() -> {
                        start.await();return new JdbcIdentityStore(jdbc).rotate(session.refreshHash(),new Session(sessionId,id,1,prefix.repeat(64),prefix.toUpperCase().repeat(64),now.plusSeconds(1200),session.refreshExpiresAt()));
                    }));
                    start.countDown();assertEquals(1,(futures.get(0).get(5,TimeUnit.SECONDS)?1:0)+(futures.get(1).get(5,TimeUnit.SECONDS)?1:0));
                }
                assertTrue(reloaded.refresh(session.refreshHash()).isEmpty());
                // Existing short-lived access tokens remain usable after refresh (including another browser tab).
                assertTrue(reloaded.access(session.accessHash()).isPresent());
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM identity_access_tokens WHERE session_id=?",Integer.class,sessionId.toString()));
                store.delete(id);assertTrue(reloaded.session(sessionId).isEmpty());
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_access_tokens WHERE session_id=?",Integer.class,sessionId.toString()));
            } finally { store.delete(id); }
        }
    }
}
