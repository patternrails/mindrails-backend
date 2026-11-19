package com.mindrails.backend.agent.api.websocket.protocol;

import java.util.UUID;

public sealed interface ClientCommand {
    // TODO: Support attachments in client commands.
    UUID requestId();

    record Chat(UUID requestId, String message, java.util.List<UUID> assetVersionIds)
            implements ClientCommand {
        public Chat {
            assetVersionIds =
                    assetVersionIds == null
                            ? java.util.List.of()
                            : java.util.List.copyOf(assetVersionIds);
        }

        public Chat(UUID requestId, String message) {
            this(requestId, message, java.util.List.of());
        }
    }

    record Cancel(UUID requestId) implements ClientCommand {}
}
