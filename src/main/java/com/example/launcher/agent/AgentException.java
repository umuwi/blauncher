package com.example.launcher.agent;

/**
 * Thrown for any non-2xx response from the VPS agent, or for transport-level
 * failures (connection refused, timeout, malformed JSON).
 *
 * Per AGENT_API.md, the agent's error bodies are inconsistent: some are
 * machine-readable codes ("invalid_key", "server_running", "not_found"),
 * others are plain human-readable messages (e.g. from /server/start,
 * /server/console/command). Callers should branch on {@link #getHttpStatus()}
 * for logic and treat {@link #getErrorCode()} purely as display text — do not
 * pattern-match on its contents beyond the few documented machine codes
 * exposed as constants below.
 */
public class AgentException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Machine-readable codes that ARE safe to branch on — see AGENT_API.md. */
    public static final String CODE_INVALID_KEY = "invalid_key";
    public static final String CODE_SERVER_RUNNING = "server_running";
    public static final String CODE_NOT_FOUND = "not_found";

    /** -1 if this exception represents a transport failure, not an HTTP response. */
    private final int httpStatus;

    /** Raw "error" field from the response body; may be a code or a free-text message. */
    private final String errorCode;

    public AgentException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
        this.errorCode = null;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** True if this failed before getting any HTTP response at all (network/timeout/parse). */
    public boolean isTransportFailure() {
        return httpStatus == -1;
    }

    public boolean isUnauthorized() {
        return httpStatus == 401;
    }

    public boolean isConflict() {
        return httpStatus == 409;
    }
}
