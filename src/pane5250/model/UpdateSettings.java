package pane5250.model;

import java.util.Objects;

public record UpdateSettings(boolean startup, Channel channel, String ignoredVersion) {
    public enum Channel {
        PREVIEW_PATCHES, STABLE;
        @Override public String toString() {
            return this==PREVIEW_PATCHES ? "Preview patches (this minor version)" : "Stable releases (this major version)";
        }
    }
    public UpdateSettings {
        Objects.requireNonNull(channel);
        Objects.requireNonNull(ignoredVersion);
        if (!ignoredVersion.isEmpty() && !ignoredVersion.matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})"))
            throw new IllegalArgumentException("Hidden update version must be empty or a numeric X.Y.Z version.");
    }
    public static UpdateSettings defaults() { return new UpdateSettings(true,Channel.PREVIEW_PATCHES,""); }
    public UpdateSettings ignore(String version) { return new UpdateSettings(startup,channel,version); }
}
