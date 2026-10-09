package cn.huohuas001.virga.api;

import java.util.Set;

/** Stable service for addons (other server mods) built against virga-api. */
public interface VirgaService {
    ApiVersion getApiVersion();
    Set<Capability> getCapabilities();

    /** Opens one owner-scoped context or fails when the API version/id is incompatible. */
    PluginContext openPlugin(PluginDescriptor descriptor);
}
