package com.example.launcher.service;

import com.example.launcher.model.ServerEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the launcher's known servers (both local processes and VPS/agent-
 * managed ones) as a single JSON file — unlike ProfileService (one file per
 * profile), the server list is naturally read/written as a whole every time
 * any single server changes, since screens like "server advanced" always
 * need the full list rendered together.
 *
 * agentKey values are stored in this file as-is. This matches how the agent
 * itself treats the key (AGENT_API.md: compared as a plain string, no
 * hashing) — the launcher isn't holding it to a higher bar than the agent
 * does, but this file should still live under the launcher's own per-user
 * data directory, not somewhere else readable.
 */
public class ServerService {

    private static final Type SERVER_LIST_TYPE = new TypeToken<List<ServerEntry>>() {}.getType();

    private final Path serversFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ServerService(Path serversFile) {
        this.serversFile = serversFile;
    }

    public List<ServerEntry> loadAll() throws IOException {
        if (!Files.exists(serversFile)) {
            if (serversFile.getParent() != null) {
                Files.createDirectories(serversFile.getParent());
            }
            return new ArrayList<>();
        }
        String json = Files.readString(serversFile);
        if (json.isBlank()) {
            return new ArrayList<>();
        }
        List<ServerEntry> servers = gson.fromJson(json, SERVER_LIST_TYPE);
        if (servers == null) {
            return new ArrayList<>();
        }
        // Runtime-only state should never survive a relaunch as "still running" —
        // if the launcher was closed while a local server was up, or a VPS agent's
        // last known state, both need to be re-confirmed, not assumed.
        for (ServerEntry s : servers) {
            s.setRunning(false);
            s.setPending(null);
        }
        return servers;
    }

    public void saveAll(List<ServerEntry> servers) throws IOException {
        if (serversFile.getParent() != null) {
            Files.createDirectories(serversFile.getParent());
        }
        // Пишем через временный файл и атомарно заменяем — если лаунчер закроют/убьют
        // посреди записи, старый список серверов не останется наполовину записанным.
        Path tmp = Files.createTempFile(serversFile.getParent(),
                serversFile.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, gson.toJson(servers, SERVER_LIST_TYPE));
            try {
                Files.move(tmp, serversFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, serversFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
