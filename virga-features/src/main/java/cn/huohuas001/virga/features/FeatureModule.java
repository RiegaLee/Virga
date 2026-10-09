package cn.huohuas001.virga.features;

/**
 * Marker for the private core-feature module.
 *
 * <p>Performance, online list, binding and inventory are introduced in the next batches. Keeping
 * the module real but empty makes the dependency boundary enforceable from the first build.</p>
 */
public final class FeatureModule {
    private FeatureModule() {
    }
}
