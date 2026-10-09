package cn.huohuas001.virga.api;

/** Completion result of an asynchronous addon command handler. */
public final class CommandResult {
    public enum Status {
        HANDLED,
        FAILED
    }

    private static final CommandResult HANDLED = new CommandResult(Status.HANDLED, null);

    private final Status status;
    private final String diagnostic;

    private CommandResult(Status status, String diagnostic) {
        this.status = status;
        this.diagnostic = diagnostic;
    }

    public static CommandResult handled() {
        return HANDLED;
    }

    public static CommandResult failed(String diagnostic) {
        return new CommandResult(Status.FAILED, diagnostic);
    }

    public Status getStatus() { return status; }
    public String getDiagnostic() { return diagnostic; }
}
