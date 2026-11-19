package com.mindrails.backend.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.identity.Identity.*;
import jakarta.servlet.http.Cookie;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class IdentityApiTest {
    static final String PASSWORD="secure-test-password-2026";
    @TempDir Path directory;
    MockMvc mvc;IdentityService service;String admin;
    JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void setup() {
        var p=new IdentityProperties();p.setAdminPassword(PASSWORD);p.setSecureCookie(false);
        service=new IdentityService(new InMemoryIdentityStore(),p);
        mvc=MockMvcBuilders.standaloneSetup(new AuthController(service,p),new UserController(service,new AvatarStorage(new FileStorageProperties(directory))))
                .setControllerAdvice(new IdentityErrorAdvice())
                .addFilters(new IdentityCorsConfiguration().identityCors(p).getFilter(),new IdentityFilter(service)).build();
        admin=service.login("admin",PASSWORD,"local").accessToken();
    }
    @Test void everyApiRequiresAuthenticationIncludingUploadsAndImages() throws Exception {
        for(String path:List.of("/api/users","/api/auth/me","/api/agent","/api/users/avatars/a.png","/api;ignored/users"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/ws-ticket").contentType("application/json").content("{}")) .andExpect(status().isUnauthorized());
    }
    @Test void loginSetsHttpOnlyCookieAndBrowserResponsesNeverRevealTheRefreshToken() throws Exception {
        var result=mvc.perform(post("/api/auth/login").header("X-MindRails-Auth","1").contentType("application/json")
                .content(json.writeValueAsString(Map.of("loginId","admin","password",PASSWORD))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        String cookie=result.getResponse().getHeader("Set-Cookie");assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Lax"));assertTrue(cookie.contains("Path=/api/auth"));
        var body=json.readTree(result.getResponse().getContentAsString());assertTrue(body.path("refreshToken").isNull());assertEquals(43,body.path("accessToken").asString().length());
        assertFalse(result.getResponse().getContentAsString().contains("passwordHash"));
        String token=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("mindrails_refresh",token)).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("mindrails_refresh",token)).header("X-MindRails-Auth","1").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("mindrails_refresh",token)).header("X-MindRails-Auth","1").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
    @Test void rawApiClientsReceiveBothTokensAndCanRevokeTheirSession() throws Exception {
        var result=mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginId","admin","password",PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString());String access=body.path("accessToken").asString(),refresh=body.path("refreshToken").asString();
        assertEquals(43,refresh.length());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").contentType("application/json").content(json.writeValueAsString(Map.of("refreshToken",refresh))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void logoutAlsoAcceptsBearerCredentialsWithoutACookie() throws Exception {
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+admin)).andExpect(status().isUnauthorized());
    }
    @Test void userCrudEnforcesPermissionsAtTheServer() throws Exception {
        var created=mvc.perform(post("/api/users").header("Authorization","Bearer "+admin).contentType("application/json")
                .content(json.writeValueAsString(Map.of("loginId","alice","name","Alice","email","alice@example.com","password",PASSWORD,"role","Normal"))))
                .andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("id").asString());
        String access=service.login("alice",PASSWORD,"alice").accessToken();
        mvc.perform(get("/api/users").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users/"+IdentityService.ADMIN_ID).header("Authorization","Bearer "+access)).andExpect(status().isOk());
        mvc.perform(put("/api/users/"+id).header("Authorization","Bearer "+access).contentType("application/json")
                .content(json.writeValueAsString(Map.of("loginId","alice","name","Alice Changed","role","SystemAdmin"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/"+id).header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/"+id).header("Authorization","Bearer "+admin)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+access)).andExpect(status().isUnauthorized());
    }
    @Test void corsAllowsCredentialsForExplicitOriginsAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/auth/refresh").header(HttpHeaders.ORIGIN,"http://localhost:3000")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,"POST").header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,"content-type,x-mindrails-auth"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,"true"));
        mvc.perform(get("/api/users").header(HttpHeaders.ORIGIN,"http://localhost:3000"))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,"http://localhost:3000"));
        mvc.perform(post("/api/auth/refresh").header(HttpHeaders.ORIGIN,"https://attacker.example").header("X-MindRails-Auth","1").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void avatarsAreValidatedReencodedAndProtected() throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"jpeg",bytes);
        var result=mvc.perform(multipart("/api/users/"+IdentityService.ADMIN_ID+"/avatar").file(new MockMultipartFile("file","../photo.svg","image/svg+xml",bytes.toByteArray()))
                .header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn();
        String url=json.readTree(result.getResponse().getContentAsString()).path("avatarUrl").asString();
        assertTrue(url.endsWith(".png"));
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(multipart("/api/users/"+IdentityService.ADMIN_ID+"/avatar").file(new MockMultipartFile("file","x.png","image/png","<svg><script>alert(1)</script></svg>".getBytes()))
                .header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/users/"+IdentityService.ADMIN_ID+"/avatar").file(new MockMultipartFile("file","big.png","image/png",new byte[(int)AvatarStorage.MAX_BYTES+1]))
                .header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        bytes.reset();ImageIO.write(new BufferedImage(2049,1,BufferedImage.TYPE_INT_RGB),"png",bytes);
        mvc.perform(multipart("/api/users/"+IdentityService.ADMIN_ID+"/avatar").file(new MockMultipartFile("file","large.png","image/png",bytes.toByteArray()))
                .header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
    }
}
