package cn.huohuas001.virga.features.binding;

/** Pure fail-closed decision for the optional login-plugin boundary. */
public final class BindingAvailability {
    private BindingAvailability() {}
    public static boolean isAvailable(boolean enabled, boolean authMeRequired, boolean authMePresent) {
        return enabled && (!authMeRequired || authMePresent);
    }
}
