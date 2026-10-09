package cn.huohuas001.virga.api;

import java.util.Set;

/** Owner-scoped capability and lifecycle boundary for one addon. */
public interface PluginContext extends AutoCloseable {
    PluginDescriptor getDescriptor();
    Set<Capability> getCapabilities();
    CommandRegistry getCommands();
    MessageGateway getMessages();
    TaskScheduler getScheduler();
    /**
     * Returns the host's read-only binding lookup capability.
     *
     * <p>The default preserves binary compatibility with API 1.0 hosts. Callers must check
     * {@link Capability#BINDING_LOOKUP} before relying on a result.</p>
     */
    default BindingService getBindings() { return BindingService.UNAVAILABLE; }
    /**
     * Returns the host-owned, constrained binding challenge workflow.
     *
     * <p>This is not a general binding writer. Addons may only create and confirm one-time
     * challenges. The default preserves compatibility with API 1.0/1.1 hosts.</p>
     */
    default BindingVerificationService getBindingVerification() {
        return BindingVerificationService.UNAVAILABLE;
    }
    PluginLogger getLogger();
    boolean isClosed();

    @Override
    void close();
}
