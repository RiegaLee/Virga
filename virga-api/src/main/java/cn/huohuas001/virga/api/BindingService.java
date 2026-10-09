package cn.huohuas001.virga.api;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Stable read-only lookup of group-scoped QQ to Minecraft account bindings. */
@FunctionalInterface
public interface BindingService {
    /** Compatibility implementation used by hosts without the binding lookup capability. */
    BindingService UNAVAILABLE = (groupId, userId) -> Optional.empty();

    /**
     * Finds the binding for exactly one group identity and one QQ user identity.
     * Implementations must not silently fall back to a global or cross-group lookup.
     */
    Optional<PlayerBinding> findBinding(String groupId, String userId);

    /**
     * Finds every binding for the exact group/user identity, in provider-defined display order.
     *
     * <p>The default preserves source and binary compatibility with API 1.2 providers: their
     * single binding is exposed as a one-element list. Multi-account providers override this
     * method and keep {@link #findBinding(String, String)} as the primary-account lookup.</p>
     */
    default List<PlayerBinding> findBindings(String groupId, String userId) {
        Optional<PlayerBinding> binding = findBinding(groupId, userId);
        return binding.isPresent()
            ? Collections.singletonList(binding.get())
            : Collections.<PlayerBinding>emptyList();
    }
}
