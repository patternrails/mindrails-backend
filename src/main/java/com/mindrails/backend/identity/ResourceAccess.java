package com.mindrails.backend.identity;

import java.util.Objects;
import java.util.UUID;

/** Resource authorization boundary. Authentication roles never grant access to another user's data.
 * Unscoped access is reserved for server maintenance/schedulers; HTTP and socket entry points always
 * establish a scope. Model callbacks establish the persisted resource owner's scope explicitly.
 */
public final class ResourceAccess {
    private static final ThreadLocal<UUID> USER = new ThreadLocal<>();
    private ResourceAccess() {}
    public static UUID userId() { return USER.get(); }
    public static UUID creationOwner() { return USER.get() == null ? IdentityService.ADMIN_ID : USER.get(); }
    public static UUID owner(UUID id) { return id == null ? IdentityService.ADMIN_ID : id; }
    public static boolean visible(UUID owner) { return USER.get() == null || sameOwner(USER.get(), owner); }
    /** Central relationship policy; future sharing rules belong here, rather than in callers. */
    public static boolean sameOwner(UUID left, UUID right) { return Objects.equals(owner(left), owner(right)); }
    public static void requireOwner(UUID owner) {
        if (!visible(owner)) throw new IdentityException(404, "Resource not found");
    }
    public static void requireBinding(UUID agentOwner, UUID resourceOwner) {
        if (!sameOwner(agentOwner, resourceOwner)) throw new IdentityException(404, "Resource not found");
    }
    /** Explicitly scoped server maintenance only; never use for user initiated operations. */
    public static Scope system() {
        var previous = USER.get(); USER.remove();
        return () -> { if (previous == null) USER.remove(); else USER.set(previous); };
    }
    public static Scope as(UUID userId) {
        var previous = USER.get(); USER.set(Objects.requireNonNull(userId));
        return () -> { if (previous == null) USER.remove(); else USER.set(previous); };
    }
    public static Runnable capture(Runnable work) {
        UUID user = USER.get();
        return user == null ? work : () -> { try (var ignored = as(user)) { work.run(); } };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
