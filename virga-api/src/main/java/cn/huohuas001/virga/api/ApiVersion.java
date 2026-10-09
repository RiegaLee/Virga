package cn.huohuas001.virga.api;

import java.util.Objects;

/** Semantic version of the stable Virga addon API. */
public final class ApiVersion implements Comparable<ApiVersion> {
    public static final ApiVersion V1_0_0 = new ApiVersion(1, 0, 0);
    public static final ApiVersion V1_1_0 = new ApiVersion(1, 1, 0);
    public static final ApiVersion V1_2_0 = new ApiVersion(1, 2, 0);
    public static final ApiVersion V1_3_0 = new ApiVersion(1, 3, 0);
    public static final ApiVersion CURRENT = V1_3_0;

    private final int major;
    private final int minor;
    private final int patch;

    public ApiVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components must be non-negative");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }

    /** Returns true when this host version can satisfy the required addon version. */
    public boolean supports(ApiVersion required) {
        Objects.requireNonNull(required, "required");
        return major == required.major && compareTo(required) >= 0;
    }

    @Override
    public int compareTo(ApiVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) return result;
        result = Integer.compare(minor, other.minor);
        if (result != 0) return result;
        return Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ApiVersion)) return false;
        ApiVersion other = (ApiVersion) value;
        return major == other.major && minor == other.minor && patch == other.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
