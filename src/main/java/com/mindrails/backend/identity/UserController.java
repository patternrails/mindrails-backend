package com.mindrails.backend.identity;

import com.mindrails.backend.identity.Identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final IdentityService identity;
    private final AvatarStorage avatars;
    public UserController(IdentityService identity,AvatarStorage avatars) { this.identity=identity;this.avatars=avatars; }
    public record UserWrite(String loginId,String name,String email,String bio,String password,String currentPassword,Role role,String language) {
        public UserWrite(String loginId,String name,String email,String bio,String password,String currentPassword,Role role) {
            this(loginId,name,email,bio,password,currentPassword,role,null);
        }
    }
    @GetMapping public List<Profile> list(HttpServletRequest request) { return identity.users(IdentityFilter.current(request)); }
    @GetMapping("/{id}") public Profile get(@PathVariable UUID id) { return identity.profile(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Profile create(@RequestBody UserWrite input,HttpServletRequest request) { return identity.create(IdentityFilter.current(request),input); }
    @PutMapping("/{id}") public Profile update(@PathVariable UUID id,@RequestBody UserWrite input,HttpServletRequest request) {
        return identity.update(IdentityFilter.current(request),id,input);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,HttpServletRequest request) {
        Current actor=IdentityFilter.current(request); IdentityService.requireAdmin(actor);
        String previous=identity.profile(id).avatarUrl();identity.delete(actor,id);avatars.discard(previous);
    }
    @PostMapping(value="/{id}/avatar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Profile upload(@PathVariable UUID id,@RequestPart("file") MultipartFile file,HttpServletRequest request) throws java.io.IOException {
        Current actor=IdentityFilter.current(request);IdentityService.requireEdit(actor,id);String previous=identity.profile(id).avatarUrl();
        String url=avatars.save(file);
        try { Profile updated=identity.avatar(actor,id,url);avatars.discard(previous);return updated; }
        catch(RuntimeException e) { avatars.discard(url);throw e; }
    }
    @DeleteMapping("/{id}/avatar") public Profile removeAvatar(@PathVariable UUID id,HttpServletRequest request) {
        Current actor=IdentityFilter.current(request);IdentityService.requireEdit(actor,id);
        String previous=identity.profile(id).avatarUrl();Profile updated=identity.avatar(actor,id,"");avatars.discard(previous);return updated;
    }
    @GetMapping("/avatars/{filename}") public ResponseEntity<byte[]> image(@PathVariable String filename) throws java.io.IOException {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff")
                .header("Cache-Control","private, max-age=300").body(avatars.read(filename));
    }
}
