package cn.huohuas001.virga.api;

/** Observable result from a text or image send operation. */
public final class SendResult {
    public enum Status {
        SUCCESS,
        NOT_CONNECTED,
        RATE_LIMITED,
        NOT_AUTHORIZED,
        INVALID_REQUEST,
        FAILED
    }

    private static final SendResult SUCCESS = new SendResult(Status.SUCCESS, null);

    private final Status status;
    private final String diagnostic;

    private SendResult(Status status, String diagnostic) {
        this.status = status;
        this.diagnostic = diagnostic;
    }

    public static SendResult success() { return SUCCESS; }
    public static SendResult of(Status status, String diagnostic) {
        if (status == Status.SUCCESS && diagnostic == null) return SUCCESS;
        return new SendResult(status, diagnostic);
    }

    public Status getStatus() { return status; }
    public String getDiagnostic() { return diagnostic; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
}
