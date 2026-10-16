package com.example.launcher.agent;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.example.launcher.agent.AgentModels.ConsoleSnapshot;
import com.example.launcher.agent.AgentModels.ServerStatus;

/**
 * Periodic polling helper for the two "watch this while the screen is open"
 * endpoints: /server/console and /server/status. One AgentPoller per open
 * serverDetail screen in the UI; call {@link #stop()} when that screen
 * closes (e.g. navigating back to serverAdvanced), or the polling thread
 * leaks for the life of the launcher.
 *
 * This does its own polling on a single-thread scheduled executor — it does
 * NOT dispatch callbacks onto the JavaFX Application thread. Wrap the
 * onUpdate/onError consumers passed in here with Platform.runLater(...) at
 * the call site in MainController — this class only guarantees the network
 * calls themselves happen off the calling thread.
 *
 * Suggested intervals (see AGENT_API.md): console ~1s, status ~3-5s.
 */
public class AgentPoller implements AutoCloseable {

    private final AgentClient client;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> consoleTask;
    private ScheduledFuture<?> statusTask;

    public AgentPoller(AgentClient client) {
        this.client = client;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "blauncher-agent-poller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts polling GET /server/console on an interval and delivering each
     * snapshot to onUpdate. onError is called (instead of onUpdate) for any
     * single failed poll — polling continues afterwards; it doesn't stop
     * itself on a transient failure.
     */
    public void startConsolePolling(Duration interval, Consumer<ConsoleSnapshot> onUpdate, Consumer<AgentException> onError) {
        stopConsolePolling();
        consoleTask = executor.scheduleWithFixedDelay(() -> {
            try {
                onUpdate.accept(client.getConsole());
            } catch (AgentException e) {
                if (onError != null) onError.accept(e);
            } catch (RuntimeException e) {
                if (onError != null) onError.accept(new AgentException("Unexpected polling error: " + e.getMessage(), e));
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stopConsolePolling() {
        if (consoleTask != null) {
            consoleTask.cancel(false);
            consoleTask = null;
        }
    }

    /**
     * Starts polling GET /server/status on an interval. Same error-handling
     * behavior as {@link #startConsolePolling}: individual failures go to
     * onError and polling continues.
     */
    public void startStatusPolling(Duration interval, Consumer<ServerStatus> onUpdate, Consumer<AgentException> onError) {
        stopStatusPolling();
        statusTask = executor.scheduleWithFixedDelay(() -> {
            try {
                onUpdate.accept(client.getStatus());
            } catch (AgentException e) {
                if (onError != null) onError.accept(e);
            } catch (RuntimeException e) {
                if (onError != null) onError.accept(new AgentException("Unexpected polling error: " + e.getMessage(), e));
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stopStatusPolling() {
        if (statusTask != null) {
            statusTask.cancel(false);
            statusTask = null;
        }
    }

    /** Stops all active polling and shuts down the internal executor. Call this when the serverDetail screen closes. */
    public void stop() {
        stopConsolePolling();
        stopStatusPolling();
        executor.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }
}
