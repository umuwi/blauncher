package com.example.launcher.service;

import com.example.launcher.model.ModInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * «Починка сборки» (18.10.2026): после вылета читает моды сборки и краш-лог и предлагает
 * конкретные действия — доустановить недостающий мод, выключить дубль или мод не того
 * загрузчика, заменить мод версией под эту версию игры.
 *
 * <p>Зачем: двое на Forge 1.20.1 падали с кодом 1 по многу раз подряд, лаунчер писал «подробности
 * в журнале», а журнал новичку ничего не говорит. Загрузчик не всегда называет причину: обязательная
 * библиотека бывает указана только на странице мода в Modrinth, а не в самом jar, — поэтому смотрим
 * три источника: метаданные jar, зависимости версии на Modrinth и строки краш-лога.
 *
 * <p>Выключение — переименование в .jar.disabled, как на экране модов: обратимо, файл не теряется.
 * Без JavaFX.
 */
public class ModDoctor {

    /** ENABLE — вернуть ранее выключенный файл (снять .disabled); file — путь к .jar.disabled. */
    public enum Kind { INSTALL, REPLACE, DISABLE, ENABLE }

    /** Одно действие плана. selected — стоит ли галочка по умолчанию (уверены ли мы). */
    public static class Fix {
        public final Kind kind;
        public final String title;        // что показать: имя мода
        public final String reason;       // почему — одной фразой
        public final Path file;           // DISABLE/REPLACE: какой файл выключить
        public final String projectId;    // INSTALL/REPLACE: что поставить
        public final String versionId;
        public boolean selected;

        Fix(Kind kind, String title, String reason, Path file, String projectId, String versionId, boolean selected) {
            this.kind = kind;
            this.title = title;
            this.reason = reason;
            this.file = file;
            this.projectId = projectId;
            this.versionId = versionId;
            this.selected = selected;
        }

        @Override public String toString() {
            return kind + " " + title + " — " + reason + (file != null ? " [" + file.getFileName() + "]" : "")
                    + (versionId != null ? " {" + projectId + "/" + versionId + "}" : "") + (selected ? "" : " (не отмечено)");
        }
    }

    /** Что лежит в одном jar. */
    static class JarMeta {
        final Path file;
        final Set<String> loaders = new HashSet<>();      // fabric, quilt, forge, neoforge
        final Set<String> ids = new LinkedHashSet<>();     // свои id
        final Set<String> provided = new HashSet<>();      // id вложенных jar и provides
        Map<String, String> requires = new LinkedHashMap<>(); // id -> диапазон (как в файле); Fabric/Quilt
        // Зависимости из mods.toml отдельно: «универсальный» jar несёт оба описания, и в Fabric-сборке
        // NeoForge-зависимость (SwingThrough → Sinytra Connector в Cobblemon) выглядела недостающей.
        Map<String, String> tomlRequires = new LinkedHashMap<>();
        String version = "";
        String name = "";

        JarMeta(Path file) { this.file = file; }
        String mainId() { return ids.isEmpty() ? "" : ids.iterator().next(); }
        String display() { return !name.isBlank() ? name : !mainId().isEmpty() ? mainId() : file.getFileName().toString(); }
    }

    /** Есть всегда: сам загрузчик, игра, Java и то, что загрузчики уже несут в себе. */
    private static final Set<String> BUILTIN = Set.of("minecraft", "java", "fabricloader", "fabric-loader",
            "quilt_loader", "forge", "neoforge", "javafml", "lowcodefml", "mixinextras", "mixin", "fml");

    /** id мода → slug на Modrinth там, где они расходятся. Остальные ищем как есть и поиском. */
    private static final Map<String, String> SLUG_ALIASES = Map.ofEntries(
            Map.entry("fabric", "fabric-api"),
            Map.entry("fabric-api", "fabric-api"),
            Map.entry("architectury", "architectury-api"),
            Map.entry("forgeconfigapiport", "forge-config-api-port"),
            Map.entry("cloth_config", "cloth-config"),
            Map.entry("cloth-config2", "cloth-config"),
            Map.entry("kotlinforforge", "kotlin-for-forge"),
            Map.entry("fabric_language_kotlin", "fabric-language-kotlin"),
            Map.entry("fabric-language-kotlin", "fabric-language-kotlin"),
            Map.entry("owo", "owo-lib"),
            Map.entry("yet_another_config_lib_v3", "yacl"),
            Map.entry("yet-another-config-lib", "yacl"),
            Map.entry("puzzleslib", "puzzles-lib"),
            Map.entry("bookshelf", "bookshelf-lib"),
            Map.entry("trinkets", "trinkets"),
            Map.entry("curios", "curios"),
            Map.entry("geckolib", "geckolib"),
            Map.entry("resourcefullib", "resourceful-lib"),
            Map.entry("moonlight", "moonlight"),
            Map.entry("ftblibrary", "ftb-library-forge"),
            Map.entry("supermartijn642corelib", "supermartijn642s-core-lib"),
            Map.entry("supermartijn642configlib", "supermartijn642s-config-lib"),
            Map.entry("iceberg", "iceberg"),
            Map.entry("prism", "prism-lib"),
            Map.entry("cristellib", "cristel-lib"),
            Map.entry("modmenu", "modmenu"),
            Map.entry("sodium", "sodium"),
            Map.entry("lithostitched", "lithostitched"),
            Map.entry("create", "create"),
            Map.entry("flywheel", "flywheel"),
            Map.entry("ponder", "ponder"),
            Map.entry("sophisticatedcore", "sophisticated-core"));

    public interface Log { void line(String msg); }

    private final ModrinthService modrinth;

    public ModDoctor(ModrinthService modrinth) {
        this.modrinth = modrinth;
    }

    // ---------------------------------------------------------------
    // Диагностика
    // ---------------------------------------------------------------

    /**
     * План починки. Сеть нужна для Modrinth; без неё остаются выводы по самим jar и логу.
     *
     * @param loader fabric / quilt / forge / neoforge (как в сборке)
     */
    public List<Fix> diagnose(String profileName, String mcVersion, String loader, String crashLog, Log log) {
        String ld = loader == null ? "" : loader.toLowerCase(Locale.ROOT);
        Path dir = modrinth.contentDir("mod", profileName);
        List<JarMeta> jars = readJars(dir);
        boolean tomlLoader = ld.equals("forge") || ld.equals("neoforge");
        for (JarMeta j : jars) if (tomlLoader) j.requires = j.tomlRequires;
        Map<Path, Fix> disables = new LinkedHashMap<>();
        Map<String, Fix> installs = new LinkedHashMap<>();   // projectId -> fix
        List<Fix> out = new ArrayList<>();

        // 1. Мод не того загрузчика — игра его либо не видит, либо падает на нём.
        // Sinytra Connector запускает Fabric-моды на NeoForge; его «full»-jar держит id во вложенном jar.
        boolean connector = jars.stream().anyMatch(j -> j.ids.contains("connector") || j.provided.contains("connector")
                || j.file.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("connector-"));
        for (JarMeta j : jars) {
            String bad = wrongLoader(j, ld, mcVersion, connector);
            if (bad != null) {
                disables.put(j.file, new Fix(Kind.DISABLE, j.display(), "мод для " + bad + ", а сборка на " + loader,
                        j.file, null, null, true));
            }
        }
        List<JarMeta> active = new ArrayList<>();
        for (JarMeta j : jars) if (!disables.containsKey(j.file)) active.add(j);

        // 2. Один и тот же мод дважды — оставляем ту копию, что подходит остальным модам.
        // 18.10.2026: в «Fabulously Optimized» лежали Sodium 0.9.2 и 0.9.3-beta.1; v1.14.16 оставляла
        // самую новую, а Reese's Sodium Options требует ровно «=0.9.2+mc26.3» — сборка перестала
        // запускаться. Пока лежали обе, Fabric сам брал подходящую. Теперь сначала требования, потом новизна.
        Map<String, List<JarMeta>> byId = new LinkedHashMap<>();
        for (JarMeta j : active) {
            if (!j.mainId().isEmpty()) byId.computeIfAbsent(j.mainId(), k -> new ArrayList<>()).add(j);
        }
        for (Map.Entry<String, List<JarMeta>> e : byId.entrySet()) {
            List<JarMeta> same = e.getValue();
            if (same.size() < 2) continue;
            List<String> wants = new ArrayList<>();
            for (JarMeta other : active) {
                if (other.ids.contains(e.getKey())) continue;
                String c = other.requires.get(e.getKey());
                if (c != null) wants.add(c);
            }
            Map<JarMeta, Integer> fits = new HashMap<>();
            for (JarMeta j : same) {
                int n = 0;
                for (String c : wants) if (satisfies(j.version, c)) n++;
                fits.put(j, n);
            }
            same.sort((a, b) -> {
                int f = Integer.compare(fits.get(b), fits.get(a));
                if (f != 0) return f;
                int c = compareVersions(b.version, a.version);
                if (c != 0) return c;
                try { return Files.getLastModifiedTime(b.file).compareTo(Files.getLastModifiedTime(a.file)); }
                catch (IOException ex) { return 0; }
            });
            JarMeta keep = same.get(0);
            for (JarMeta dup : same.subList(1, same.size())) {
                disables.put(dup.file, new Fix(Kind.DISABLE, dup.display() + " " + dup.version,
                        "лежит дважды — оставляем " + keep.version + " (" + keep.file.getFileName() + ")",
                        dup.file, null, null, true));
            }
        }
        active.removeIf(j -> disables.containsKey(j.file));

        // Что в сборке уже есть (по id из jar).
        Set<String> present = new HashSet<>(BUILTIN);
        for (JarMeta j : active) { present.addAll(j.ids); present.addAll(j.provided); }
        // Без разделителей: Forgified Fabric API зовётся fabric_api, а Fabric-моды требуют fabric-api.
        Set<String> presentNorm = new HashSet<>();
        for (String id : present) presentNorm.add(norm(id));

        // 3. Обязательные моды из самих jar.
        Map<String, String> missingIds = new LinkedHashMap<>();   // id -> кому нужен
        // id -> диапазоны версий, которые просят моды сборки. 18.10.2026: Iris для 1.21.1 требует
        // Sodium «0.8.x», а свежий Sodium под 1.21.1 — уже другой ветки; поставь мы самый новый,
        // следующий запуск упал бы снова.
        Map<String, List<String>> ranges = new HashMap<>();
        for (JarMeta j : active) {
            for (Map.Entry<String, String> d : j.requires.entrySet()) {
                String dep = d.getKey();
                if (!present.contains(dep) && !isBuiltinLike(dep)) {
                    String id = canonicalId(dep);
                    if (!present.contains(id) && !presentNorm.contains(norm(id)) && !presentNorm.contains(norm(dep))) {
                        missingIds.putIfAbsent(id, j.display());
                        if (id.equals(dep)) ranges.computeIfAbsent(id, k -> new ArrayList<>()).add(d.getValue());
                    }
                }
            }
        }
        Map<String, String> idOfProject = new HashMap<>();

        // 4. Краш-лог: что сказал сам загрузчик.
        Set<String> replaceIds = new LinkedHashSet<>();
        Set<String> suspectIds = new LinkedHashSet<>();
        List<Fix> exact = new ArrayList<>();   // точная версия, которую назвал загрузчик
        if (crashLog != null && !crashLog.isEmpty()) {
            List<JarMeta> disabledJars = readJars(dir, ".jar.disabled");
            for (ModConflictAnalyzer.Suggestion s : ModConflictAnalyzer.analyze(crashLog)) {
                if ((s.action == ModConflictAnalyzer.Action.REPLACE || s.action == ModConflictAnalyzer.Action.INSTALL)
                        && present.contains(s.modId)
                        && wantedVersion(s.detail, s.modId, active, disabledJars, mcVersion, ld, disables, exact)) {
                    continue;
                }
                switch (s.action) {
                    case INSTALL -> { if (!present.contains(s.modId)) missingIds.putIfAbsent(s.modId, "загрузчик"); else replaceIds.add(s.modId); }
                    case REPLACE -> { if (!present.contains(s.modId)) missingIds.putIfAbsent(s.modId, "загрузчик"); else replaceIds.add(s.modId); }
                    case REMOVE -> {
                        for (JarMeta j : active) {
                            if (j.ids.contains(s.modId) && !disables.containsKey(j.file)) {
                                disables.put(j.file, new Fix(Kind.DISABLE, j.display(), "загрузчик просит убрать: несовместим",
                                        j.file, null, null, true));
                            }
                        }
                    }
                }
            }
            Matcher m = FORGE_DEP.matcher(crashLog);
            while (m.find()) {
                String id = m.group(1);
                if ("[MISSING]".equalsIgnoreCase(m.group(4)) || !present.contains(id)) missingIds.putIfAbsent(id, m.group(2));
                else replaceIds.add(id);
            }
            m = MIXIN_MOD.matcher(crashLog);
            while (m.find()) suspectIds.add(m.group(1).toLowerCase(Locale.ROOT));
            m = SUSPECTED.matcher(crashLog);
            while (m.find()) suspectIds.add(m.group(1).toLowerCase(Locale.ROOT));
            suspectIds.removeAll(BUILTIN);
        }

        // 5. Modrinth: зависимости, которые автор указал на сайте, и версия игры у каждого мода.
        Map<String, JsonObject> versions = Map.of();
        Map<Path, String> hashes = new HashMap<>();
        try {
            for (JarMeta j : active) hashes.put(j.file, ModrinthService.sha1Of(j.file));
            versions = modrinth.versionsByHash(hashes.values());
        } catch (Exception e) {
            log.line("[FIX] Modrinth недоступен (" + e.getMessage() + ") — проверяю только по файлам модов");
        }
        Set<String> presentProjects = new HashSet<>();
        for (JarMeta j : active) {
            JsonObject v = versions.get(hashes.get(j.file));
            if (v != null) presentProjects.add(str(v, "project_id"));
        }
        // projectId -> кому нужен. Из Modrinth — только если есть версия под этот загрузчик:
        // зависимости там общие на все загрузчики версии, и мод «Fabric+Forge» требует Fabric API
        // даже в Forge-сборке. Такие молча пропускаем, а не советуем невозможное.
        Map<String, String> missingProjects = new LinkedHashMap<>();
        Set<String> fromModrinthOnly = new HashSet<>();
        for (JarMeta j : active) {
            JsonObject v = versions.get(hashes.get(j.file));
            if (v == null) continue;
            if (v.has("dependencies") && v.get("dependencies").isJsonArray()) {
                for (JsonElement el : v.getAsJsonArray("dependencies")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject d = el.getAsJsonObject();
                    if (!"required".equals(str(d, "dependency_type"))) continue;
                    String pid = str(d, "project_id");
                    if (!pid.isEmpty() && !presentProjects.contains(pid) && !missingProjects.containsKey(pid)) {
                        missingProjects.put(pid, j.display());
                        fromModrinthOnly.add(pid);
                    }
                }
            }
            // Мод не под эту версию игры. Решает то, что мод пишет о себе сам (depends.minecraft):
            // 18.10.2026 MixinTrace «1.0-26.1» на Modrinth отмечен только для 26.1, а в jar — «26.x»,
            // и на 26.3 он работает; «Починка» предлагала его выключить. Placeholder API «>=26.2-»
            // так же зря менялась. Список версий на Modrinth — лишь повод предложить обновление.
            if (mcVersion != null && v.has("game_versions") && !jsonHas(v.getAsJsonArray("game_versions"), mcVersion)) {
                String declared = j.requires.get("minecraft");
                if (declared != null && satisfies(mcVersion, declared)) continue;
                String pid = str(v, "project_id");
                String best = bestVersion(pid, mcVersion, ld);
                if (declared != null) {
                    // Сам мод говорит «не для этой версии» — загрузчик его всё равно не пустит.
                    if (best != null && !best.equals(str(v, "id"))) {
                        out.add(new Fix(Kind.REPLACE, j.display(), "версия " + str(v, "version_number") + " не для Minecraft "
                                + mcVersion + " — заменить подходящей", j.file, pid, best, true));
                    } else if (best == null && !disables.containsKey(j.file)) {
                        // Modrinth не ответил — подходящая версия может быть: выключать не отмечено.
                        boolean noAnswer = lookupFailed.contains(pid);
                        disables.put(j.file, new Fix(Kind.DISABLE, j.display(), "мод не поддерживает Minecraft "
                                + mcVersion + (noAnswer ? " (Modrinth не ответил — проверьте позже)" : ", а подходящей версии нет"),
                                j.file, null, null, !noAnswer));
                    }
                } else if (best != null && !best.equals(str(v, "id"))) {
                    out.add(new Fix(Kind.REPLACE, j.display(), "на Modrinth версия " + str(v, "version_number")
                            + " отмечена не для " + mcVersion + " — есть версия для неё (обновить по желанию)",
                            j.file, pid, best, false));
                }
            }
        }
        Set<Path> replaced = new HashSet<>();
        for (Fix f : out) replaced.add(f.file);

        // Мод, который загрузчик назвал «не той версии» / в чьём миксине упала игра: свежая версия.
        Set<String> toRefresh = new LinkedHashSet<>(replaceIds);
        toRefresh.addAll(suspectIds);
        for (String id : toRefresh) {
            for (JarMeta j : active) {
                if (!j.ids.contains(id) || replaced.contains(j.file) || disables.containsKey(j.file)) continue;
                JsonObject v = versions.get(hashes.get(j.file));
                boolean confident = replaceIds.contains(id);
                String pid = v != null ? str(v, "project_id") : resolveProject(id, mcVersion, ld);
                String best = pid == null ? null : bestVersion(pid, mcVersion, ld);
                if (best != null && (v == null || !best.equals(str(v, "id")))) {
                    out.add(new Fix(Kind.REPLACE, j.display(), confident ? "загрузчик требует другую версию"
                            : "на нём упала игра — обновить до свежей версии", j.file, pid, best, confident));
                    replaced.add(j.file);
                } else if (!confident) {
                    disables.put(j.file, new Fix(Kind.DISABLE, j.display(), "на нём упала игра, а новее версии нет — выключить",
                            j.file, null, null, false));
                }
            }
        }

        // Недостающие по id → проект Modrinth.
        for (Map.Entry<String, String> e : missingIds.entrySet()) {
            String pid = resolveProject(e.getKey(), mcVersion, ld);
            if (pid == null) {
                out.add(new Fix(Kind.INSTALL, e.getKey(), "нужен для " + e.getValue() + ", но на Modrinth под "
                        + loader + " " + mcVersion + " не найден — поставьте вручную", null, null, null, false));
                continue;
            }
            if (!presentProjects.contains(pid)) { missingProjects.putIfAbsent(pid, e.getValue()); fromModrinthOnly.remove(pid); }
            idOfProject.putIfAbsent(pid, e.getKey());
        }
        if (!missingProjects.isEmpty()) {
            // Имена всех модов сборки без разделителей: мод с CurseForge Modrinth по хешу не узнаёт,
            // и без этой сверки мы поставили бы его второй копией.
            for (JarMeta j : active) {
                for (String id : j.ids) presentNorm.add(norm(id));
                for (String id : j.provided) presentNorm.add(norm(id));
                if (!j.name.isBlank()) presentNorm.add(norm(j.name));
            }
            for (Map.Entry<String, String> a : SLUG_ALIASES.entrySet()) {
                if (presentNorm.contains(norm(a.getKey()))) presentNorm.add(norm(a.getValue()));
            }
            for (Map.Entry<String, String> e : missingProjects.entrySet()) {
                JsonObject proj = null;
                try { proj = modrinth.project(e.getKey()); } catch (Exception ignored) { }
                String title = proj != null && !str(proj, "title").isEmpty() ? str(proj, "title") : e.getKey();
                if (proj != null && (presentNorm.contains(norm(str(proj, "slug"))) || presentNorm.contains(norm(title)))) continue;
                List<String> want = ranges.getOrDefault(idOfProject.getOrDefault(e.getKey(), ""), List.of());
                String best = want.isEmpty() ? bestVersion(e.getKey(), mcVersion, ld)
                        : versionWithin(e.getKey(), mcVersion, ld, want);
                if (best == null && fromModrinthOnly.contains(e.getKey())) continue;
                // Только Modrinth говорит «обязателен», а в самом моде этого нет: авторы часто
                // отмечают так «очень желательно». Ставим галочку, лишь если мод упомянут в логе вылета.
                boolean sure = !fromModrinthOnly.contains(e.getKey()) || mentioned(crashLog, proj, e.getKey());
                if (best == null) {
                    out.add(new Fix(Kind.INSTALL, title, "нужен для " + e.getValue() + ", но под " + loader + " "
                            + mcVersion + " его нет", null, e.getKey(), null, false));
                } else {
                    installs.put(e.getKey(), new Fix(Kind.INSTALL, title, sure ? "нужен для " + e.getValue()
                            : "на Modrinth указан обязательным для " + e.getValue() + " (в самом моде не прописан)",
                            null, e.getKey(), best, sure));
                }
            }
        }
        // «Поставьте вручную» для мода, который другим путём уже нашёлся и ставится: id в моде
        // «sophisticatedcore», на Modrinth — «Sophisticated Core» (18.10.2026, своя NeoForge-сборка).
        Set<String> coming = new HashSet<>();
        for (Fix f : installs.values()) coming.add(norm(f.title));
        out.removeIf(f -> f.kind == Kind.INSTALL && f.projectId == null && coming.contains(norm(f.title)));
        List<Fix> plan = new ArrayList<>(installs.values());
        plan.addAll(exact);
        plan.addAll(out);
        plan.addAll(disables.values());
        return plan;
    }

    // «Replace mod 'Sodium' (sodium) 0.9.3 with version 0.9.2+mc26.3.» / «… requires version 0.9.2+mc26.3 of mod …»
    private static final Pattern WANT_EXACT = Pattern.compile(
            "(?:with version|requires version) (\\S+?)\\.?(?:\\s|$| of )");

    /**
     * Загрузчик назвал конкретную версию мода, который в сборке есть, но другой версии. Самая свежая
     * версия тут не годится (её-то он и отверг), поэтому: выключенная копия этой версии лежит рядом —
     * включаем её; нет — ищем ровно эту версию на Modrinth. true — предложение обработано.
     */
    private boolean wantedVersion(String detail, String id, List<JarMeta> active, List<JarMeta> disabledJars,
                                  String mc, String loader, Map<Path, Fix> disables, List<Fix> exact) {
        if (detail == null) return false;
        Matcher m = WANT_EXACT.matcher(detail);
        if (!m.find()) return false;
        String want = m.group(1);
        for (Fix f : exact) if (f.title.equals(id + " " + want)) return true;
        List<JarMeta> current = new ArrayList<>();
        for (JarMeta j : active) if (j.ids.contains(id)) current.add(j);
        if (current.isEmpty()) return false;
        for (JarMeta d : disabledJars) {
            if (d.ids.contains(id) && sameVersion(d.version, want)) {
                exact.add(new Fix(Kind.ENABLE, id + " " + want, "загрузчик просит именно эту версию — она лежит выключенной, включаем",
                        d.file, null, null, true));
                for (JarMeta c : current) {
                    disables.put(c.file, new Fix(Kind.DISABLE, c.display() + " " + c.version,
                            "вместо неё включается " + want, c.file, null, null, true));
                }
                return true;
            }
        }
        String pid = resolveProject(id, mc, loader);
        if (pid == null) return false;
        try {
            for (JsonElement el : modrinth.listVersions(pid, mc, loader)) {
                JsonObject v = el.getAsJsonObject();
                if (sameVersion(str(v, "version_number"), want)) {
                    JarMeta c = current.get(0);
                    exact.add(new Fix(Kind.REPLACE, c.display(), "загрузчик просит версию " + want,
                            c.file, pid, str(v, "id"), true));
                    for (JarMeta extra : current.subList(1, current.size())) {
                        disables.put(extra.file, new Fix(Kind.DISABLE, extra.display(), "вторая копия", extra.file, null, null, true));
                    }
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * Самая новая версия проекта под эту сборку, подходящая под все диапазоны; ни одна не подходит —
     * просто самая новая (лучше поставить что-то, а загрузчик назовёт точную версию в следующий раз).
     */
    private String versionWithin(String projectId, String mc, String loader, List<String> want) {
        try {
            for (JsonElement el : modrinth.listVersions(projectId, mc, loader)) {
                JsonObject v = el.getAsJsonObject();
                String ver = modVersionOf(str(v, "version_number"), mc);
                boolean ok = true;
                for (String c : want) if (!satisfies(ver, c)) { ok = false; break; }
                if (ok) return str(v, "id");
            }
        } catch (Exception ignored) { }
        return bestVersion(projectId, mc, loader);
    }

    private static final Pattern VERSION_TOKEN = Pattern.compile("\\d+(?:\\.\\d+)+(?:-(?:alpha|beta|rc|pre)[.\\d]*)?");

    /**
     * Номер версии самого мода из version_number Modrinth, где бывает всякое: «mc1.21.1-0.6.13-fabric»,
     * «1.8.14-beta.1+1.21.1-fabric», «15.10.6+fabric». Берём первое число-версию, не совпадающее с версией игры.
     */
    static String modVersionOf(String versionNumber, String mc) {
        if (versionNumber == null) return "";
        Matcher m = VERSION_TOKEN.matcher(versionNumber);
        String first = null;
        while (m.find()) {
            String t = m.group();
            if (first == null) first = t;
            if (!t.equals(mc) && !(mc != null && t.startsWith(mc + "-"))) return t;
        }
        return first != null ? first : versionNumber;
    }

    private static boolean sameVersion(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || stripBuild(a).equals(stripBuild(b));
    }

    private static String stripBuild(String v) {
        int plus = v.indexOf('+');
        return plus < 0 ? v : v.substring(0, plus);
    }

    /**
     * Подходит ли версия под условие из fabric.mod.json: строка («>=0.9.2», «=0.9.2+mc26.3», «0.9.x»,
     * «~1.2», «^1.2», «*», условия через пробел — «И») или JSON-массив строк («ИЛИ»).
     * Сравнение грубое (compareVersions), но для выбора между двумя копиями мода его хватает.
     */
    static boolean satisfies(String version, String constraintJson) {
        if (version == null || version.isBlank() || constraintJson == null) return true;
        List<String> alternatives = new ArrayList<>();
        try {
            JsonElement el = JsonParser.parseString(constraintJson);
            if (el.isJsonArray()) {
                for (JsonElement a : el.getAsJsonArray()) alternatives.add(a.getAsString());
            } else {
                alternatives.add(el.getAsString());
            }
        } catch (Exception e) {
            alternatives.add(constraintJson.replace("\"", ""));
        }
        String v = stripBuild(version);
        for (String alt : alternatives) {
            boolean all = true;
            for (String part : alt.trim().split("\\s+")) {
                if (!part.isEmpty() && !satisfiesOne(v, part)) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean satisfiesOne(String v, String c) {
        if (c.equals("*")) return true;
        String op = c.startsWith(">=") || c.startsWith("<=") ? c.substring(0, 2)
                : c.startsWith(">") || c.startsWith("<") || c.startsWith("=") || c.startsWith("~") || c.startsWith("^")
                ? c.substring(0, 1) : "";
        String target = stripBuild(c.substring(op.length()));
        if (target.endsWith(".x") || target.endsWith(".*")) {
            String prefix = target.substring(0, target.length() - 1);
            // «1.21.x» включает и саму 1.21 (так считает Fabric); раньше 1.21 не проходила, и
            // «Починка» предлагала выключить подходящий мод (18.10.2026).
            return v.startsWith(prefix) || v.equals(prefix.substring(0, prefix.length() - 1));
        }
        int cmp = compareVersions(v, target);
        String[] t = target.split("\\.");
        switch (op) {
            case ">=": return cmp >= 0;
            case "<=": return cmp <= 0;
            case ">":  return cmp > 0;
            case "<":  return cmp < 0;
            // «~1.21.0» — та же 1.21.*, включая «1.21» без третьей цифры.
            case "~":  return cmp >= 0 && (t.length >= 2
                    ? v.startsWith(t[0] + "." + t[1] + ".") || v.equals(t[0] + "." + t[1]) : v.equals(target));
            case "^":  return cmp >= 0 && v.startsWith(t[0] + ".");
            // «=x» и просто «x» — ровно эта версия; «1.21» = «1.21.0», но «1.0a» не равна «1.0».
            default:   return v.equals(target)
                    || cmp == 0 && v.matches("[0-9.]+") && target.matches("[0-9.]+");
        }
    }

    /**
     * Что не вышло в последнем apply(), метками «ДЕЙСТВИЕ:Исключение» (INSTALL:ConnectException) —
     * для статистики. 19.10.2026 у c2e80bea все 10 исправлений не удались разом («failed=10»), а
     * почему — по статистике понять было нельзя; текст ошибок не шлём, в нём пути с именем
     * пользователя Windows.
     */
    private volatile List<String> lastFailKinds = List.of();

    public List<String> lastFailKinds() {
        return lastFailKinds;
    }

    /** Применяет отмеченное. Возвращает список того, что не вышло. */
    public List<String> apply(List<Fix> plan, String profileName, Log log) {
        List<String> failed = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (Fix f : plan) {
            if (!f.selected) continue;
            try {
                switch (f.kind) {
                    case INSTALL -> {
                        if (f.versionId == null) continue;
                        modrinth.downloadContent(f.versionId, profileName, "mod", f.projectId, f.title, log::line);
                        log.line("[FIX] Установлен «" + f.title + "»");
                    }
                    case REPLACE -> {
                        // Новая версия может называться так же, как старый файл (тот же мод с другого
                        // сайта): downloadContent тогда ничего не качает — «уже есть», и выключение
                        // убрало бы единственную копию. Такой файл сперва убираем, потом качаем
                        // (18.10.2026).
                        Path aside = null;
                        if (f.file != null && Files.exists(f.file)) {
                            aside = f.file.resolveSibling(f.file.getFileName() + ".doctor-old");
                            Files.move(f.file, aside, StandardCopyOption.REPLACE_EXISTING);
                        }
                        try {
                            modrinth.downloadContent(f.versionId, profileName, "mod", f.projectId, f.title, log::line);
                        } catch (Exception e) {
                            if (aside != null) Files.move(aside, f.file, StandardCopyOption.REPLACE_EXISTING);
                            throw e;
                        }
                        if (aside != null) {
                            if (Files.exists(f.file)) {
                                // Новая версия встала на то же имя — старая копия уходит в .disabled.
                                Files.move(aside, disabledName(f.file));
                            } else {
                                Files.move(aside, f.file);
                                disable(f.file);
                            }
                        }
                        log.line("[FIX] «" + f.title + "» заменён подходящей версией, старый файл выключен");
                    }
                    case DISABLE -> {
                        disable(f.file);
                        log.line("[FIX] Выключен " + f.file.getFileName() + " (" + f.reason + ")");
                    }
                    case ENABLE -> {
                        String name = f.file.getFileName().toString();
                        Path target = f.file.resolveSibling(name.substring(0, name.length() - ".disabled".length()));
                        if (Files.exists(f.file) && !Files.exists(target)) Files.move(f.file, target);
                        log.line("[FIX] Включён обратно " + target.getFileName());
                    }
                }
            } catch (Exception e) {
                failed.add(f.title + ": " + e.getMessage());
                kinds.add(f.kind + ":" + e.getClass().getSimpleName());
                log.line("[FIX] Не получилось: " + f.title + " — " + e.getMessage());
            }
        }
        lastFailKinds = kinds;
        return failed;
    }

    private static void disable(Path jar) throws IOException {
        if (jar == null || !Files.exists(jar)) return;
        Files.move(jar, disabledName(jar));
    }

    /**
     * Свободное имя для выключенного файла. Раньше X.jar.disabled перезаписывался — пропадала
     * копия, которую выключили ещё раньше (18.10.2026); теперь X (2).jar.disabled и т.д.
     */
    private static Path disabledName(Path jar) {
        Path target = jar.resolveSibling(jar.getFileName() + ".disabled");
        String name = jar.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; Files.exists(target); i++) {
            target = jar.resolveSibling(base + " (" + i + ")" + ext + ".disabled");
        }
        return target;
    }

    // ---------------------------------------------------------------
    // Строки краш-лога
    // ---------------------------------------------------------------

    /**
     * Загрузчик отказался грузить моды. Это не всегда «вылет»: Forge/NeoForge показывают свой экран
     * ошибки внутри игры, Fabric — отдельное окно, и, закрыв его, игрок получает код выхода 0.
     * 18.10.2026 на своей тестовой Forge-сборке без Citadel/GeckoLib/Balm лаунчер так и не предложил
     * «Починку» — смотрел только на код выхода.
     */
    public static boolean loaderRefused(String log) {
        if (log == null) return false;
        return log.contains("Incompatible mods found")
                || log.contains("Missing or unsupported mandatory dependencies")
                || log.contains("Mod resolution failed")
                || log.contains("Mod resolution encountered an incompatible mod set");
    }

    // Forge/NeoForge: «Mod ID: 'geckolib', Requested by: 'mowziesmobs', Expected range: '[4.4,)', Actual version: '[MISSING]'»
    static final Pattern FORGE_DEP = Pattern.compile(
            "Mod ID: '([a-z0-9_\\-]+)', Requested by: '([a-z0-9_\\-]+)', Expected range: '([^']*)', Actual version: '([^']*)'");
    // «Mixin apply for mod sodium failed» / «Mixin [x.mixins.json:Foo] from mod iris failed injection check»
    static final Pattern MIXIN_MOD = Pattern.compile(
            "(?:Mixin apply for mod |\\] from mod )([a-z0-9_\\-]+)");
    // NeoForge/Fabric crash report: «Suspected Mods: Create (create), Version: 6.0.4»
    static final Pattern SUSPECTED = Pattern.compile(
            "Suspected Mods?:\\s*[^\\n(]*\\(([a-z0-9_\\-]+)\\)");

    // ---------------------------------------------------------------
    // Чтение jar
    // ---------------------------------------------------------------

    static List<JarMeta> readJars(Path dir) {
        return readJars(dir, ".jar");
    }

    static List<JarMeta> readJars(Path dir, String suffix) {
        List<JarMeta> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)) continue;
                JarMeta m = new JarMeta(f);
                try (ZipFile zip = new ZipFile(f.toFile())) {
                    readInto(m, zip);
                } catch (Exception e) {
                    // битый jar — его найдёт crashReason («corrupt»), здесь просто пропускаем
                }
                out.add(m);
            }
        } catch (IOException ignored) { }
        out.sort(Comparator.comparing(j -> j.file.getFileName().toString()));
        return out;
    }

    private static void readInto(JarMeta m, ZipFile zip) throws IOException {
        ZipEntry fabric = zip.getEntry("fabric.mod.json");
        if (fabric != null) parseFabric(m, read(zip, fabric), true);
        ZipEntry quilt = zip.getEntry("quilt.mod.json");
        if (quilt != null) parseQuilt(m, read(zip, quilt), true);
        ZipEntry neo = zip.getEntry("META-INF/neoforge.mods.toml");
        if (neo != null) { m.loaders.add("neoforge"); parseToml(m, read(zip, neo), true); }
        ZipEntry forge = zip.getEntry("META-INF/mods.toml");
        if (forge != null) { m.loaders.add("forge"); parseToml(m, read(zip, forge), true); }
        // «Универсальные» jar грузятся через сервисы модлаунчера, а не через mods.toml:
        // так устроен CustomSkinLoader_Universal, который ставит сам лаунчер, — без этой
        // проверки он выглядел бы «модом для Fabric» в Forge-сборке.
        ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
        if (mf != null) {
            String manifest = read(zip, mf);
            if (manifest.contains("FMLModType") || manifest.contains("FMLCorePlugin")) { m.loaders.add("forge"); m.loaders.add("neoforge"); }
        }
        var services = zip.entries();
        while (services.hasMoreElements()) {
            String n = services.nextElement().getName();
            if (!n.startsWith("META-INF/services/")) continue;
            if (n.contains("cpw.mods.modlauncher")) { m.loaders.add("forge"); m.loaders.add("neoforge"); }
            else if (n.contains("net.neoforged")) m.loaders.add("neoforge");
            else if (n.contains("net.minecraftforge")) m.loaders.add("forge");
        }
        // Вложенные jar: их id тоже «есть в сборке» (Fabric API несёт десятки модулей внутри).
        var en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (!n.endsWith(".jar") || !(n.startsWith("META-INF/jars/") || n.startsWith("META-INF/jarjar/")
                    || n.startsWith("META-INF/jarsinjar/"))) continue;
            try (InputStream in = zip.getInputStream(e)) {
                readNested(m, in.readAllBytes(), 0);
            } catch (Exception ignored) { }
        }
    }

    private static void readNested(JarMeta owner, byte[] jar, int depth) throws IOException {
        if (depth > 2) return;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("fabric.mod.json") || n.equals("quilt.mod.json") || n.equals("META-INF/mods.toml")
                        || n.equals("META-INF/neoforge.mods.toml")) {
                    JarMeta tmp = new JarMeta(owner.file);
                    String text = new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                    if (n.equals("fabric.mod.json")) parseFabric(tmp, text, false);
                    else if (n.equals("quilt.mod.json")) parseQuilt(tmp, text, false);
                    else parseToml(tmp, text, false);
                    owner.provided.addAll(tmp.ids);
                    owner.provided.addAll(tmp.provided);
                } else if (n.endsWith(".jar") && n.startsWith("META-INF/")) {
                    readNested(owner, zin.readAllBytes(), depth + 1);
                }
            }
        }
    }

    private static String read(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void parseFabric(JarMeta m, String json, boolean top) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (top) m.loaders.add("fabric");
            String id = str(o, "id");
            if (!id.isEmpty()) m.ids.add(id);
            if (top) { m.version = str(o, "version"); m.name = str(o, "name"); }
            if (o.has("provides") && o.get("provides").isJsonArray()) {
                for (JsonElement p : o.getAsJsonArray("provides")) if (p.isJsonPrimitive()) m.provided.add(p.getAsString());
            }
            if (top && o.has("depends") && o.get("depends").isJsonObject()) {
                for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("depends").entrySet()) {
                    m.requires.put(d.getKey(), d.getValue().toString());
                }
            }
        } catch (Exception ignored) { }
    }

    static void parseQuilt(JarMeta m, String json, boolean top) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject o = root.has("quilt_loader") ? root.getAsJsonObject("quilt_loader") : root;
            if (top) m.loaders.add("quilt");
            String id = str(o, "id");
            if (!id.isEmpty()) m.ids.add(id);
            if (top) m.version = str(o, "version");
            if (o.has("provides") && o.get("provides").isJsonArray()) {
                for (JsonElement p : o.getAsJsonArray("provides")) {
                    if (p.isJsonPrimitive()) m.provided.add(p.getAsString());
                    else if (p.isJsonObject()) m.provided.add(str(p.getAsJsonObject(), "id"));
                }
            }
            if (top && o.has("depends") && o.get("depends").isJsonArray()) {
                for (JsonElement d : o.getAsJsonArray("depends")) {
                    if (d.isJsonPrimitive()) m.requires.put(d.getAsString(), "*");
                    else if (d.isJsonObject() && !d.getAsJsonObject().has("optional")) {
                        String did = str(d.getAsJsonObject(), "id");
                        if (!did.isEmpty()) m.requires.put(did.contains(":") ? did.substring(did.indexOf(':') + 1) : did, "*");
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private static final Pattern TOML_KV = Pattern.compile("^\\s*([A-Za-z]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))");

    /**
     * mods.toml без настоящего TOML-парсера: нужны только [[mods]] modId/version/displayName и
     * [[dependencies.x]] modId + mandatory=true (Forge) / type="required" (NeoForge 1.20.5+).
     */
    static void parseToml(JarMeta m, String toml, boolean top) {
        String section = "";
        String depId = null;
        boolean depRequired = false;
        boolean depClientOnly = false;
        for (String raw : toml.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (line.startsWith("[")) {
                if (depId != null && depRequired && !depClientOnly && top) m.tomlRequires.put(depId, "*");
                depId = null; depRequired = false; depClientOnly = false;
                section = line.startsWith("[[mods]]") ? "mods" : line.startsWith("[[dependencies") ? "dep" : "other";
                continue;
            }
            Matcher kv = TOML_KV.matcher(line);
            if (!kv.find()) continue;
            String key = kv.group(1);
            String val = kv.group(2) != null ? kv.group(2) : kv.group(3) != null ? kv.group(3) : kv.group(4);
            if ("mods".equals(section)) {
                if ("modId".equals(key)) m.ids.add(val);
                else if (top && "version".equals(key) && m.version.isEmpty()) m.version = val;
                else if (top && "displayName".equals(key) && m.name.isEmpty()) m.name = val;
            } else if ("dep".equals(section)) {
                if ("modId".equals(key)) depId = val;
                else if ("mandatory".equals(key)) depRequired = "true".equals(val);
                else if ("type".equals(key)) depRequired = "required".equalsIgnoreCase(val);
                else if ("side".equals(key)) depClientOnly = "SERVER".equalsIgnoreCase(val);
            }
        }
        if (depId != null && depRequired && !depClientOnly && top) m.tomlRequires.put(depId, "*");
    }

    /** Загрузчик, для которого этот jar, если он точно не для {@code loader}; иначе null. */
    static String wrongLoader(JarMeta j, String loader, String mc, boolean connector) {
        if (j.loaders.isEmpty()) return null;   // библиотека без метаданных — не судим
        Set<String> l = j.loaders;
        switch (loader) {
            case "fabric":
                if (l.contains("fabric")) return null;
                return l.contains("quilt") ? "Quilt" : l.contains("neoforge") ? "NeoForge" : "Forge";
            case "quilt":
                if (l.contains("fabric") || l.contains("quilt")) return null;
                return l.contains("neoforge") ? "NeoForge" : "Forge";
            case "forge":
                if (l.contains("forge")) return null;
                if ((l.contains("fabric") || l.contains("quilt")) && connector) return null;
                return l.contains("neoforge") ? "NeoForge" : "Fabric";
            case "neoforge":
                if (l.contains("neoforge")) return null;
                // NeoForge до 1.20.5 читает META-INF/mods.toml от Forge — такие моды там работают.
                if (l.contains("forge") && compareVersions(mc, "1.20.5") < 0) return null;
                if ((l.contains("fabric") || l.contains("quilt")) && connector) return null;
                return l.contains("forge") ? "Forge" : "Fabric";
            default:
                return null;
        }
    }

    private static boolean isBuiltinLike(String id) {
        return BUILTIN.contains(id);
    }

    /** Модули Fabric API (fabric-networking-api-v1, fabric-api-base…) — это сам Fabric API. */
    static String canonicalId(String id) {
        if (id.equals("fabric") || id.equals("fabric-api-base")
                || id.startsWith("fabric-") && id.matches("fabric-[a-z0-9\\-]+-v\\d+")) return "fabric-api";
        return id;
    }

    // ---------------------------------------------------------------
    // Modrinth
    // ---------------------------------------------------------------

    /** id мода → projectId на Modrinth, у которого есть версия под эту сборку; не нашли — null. */
    String resolveProject(String modId, String mc, String loader) {
        List<String> tries = new ArrayList<>();
        String alias = SLUG_ALIASES.get(modId);
        if (alias != null) tries.add(alias);
        tries.add(modId);
        tries.add(modId.replace('_', '-'));
        for (String slug : new LinkedHashSet<>(tries)) {
            try {
                JsonObject p = modrinth.project(slug);
                if (p == null) continue;
                String pid = str(p, "id");
                if (bestVersion(pid, mc, loader) != null) return pid;
            } catch (Exception ignored) { }
        }
        // Поиском: берём попадание, чьё имя или slug совпадает с id без разделителей.
        try {
            String want = norm(modId);
            for (ModInfo hit : modrinth.search(modId.replace('_', ' '), mc, loader, "mod", 10)) {
                if (want.equals(norm(hit.getSlug())) || want.equals(norm(hit.getTitle()))) return hit.getProjectId();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private final Map<String, Optional<String>> bestCache = new HashMap<>();

    /** Проекты, о которых Modrinth не ответил (сеть, 429): «версии нет» про них не утверждаем. */
    private final Set<String> lookupFailed = new HashSet<>();

    private String bestVersion(String projectId, String mc, String loader) {
        String key = projectId + "|" + mc + "|" + loader;
        Optional<String> cached = bestCache.get(key);
        if (cached != null) return cached.orElse(null);
        try {
            String best = modrinth.resolveBestVersion(projectId, mc, loader);
            bestCache.put(key, Optional.ofNullable(best));
            lookupFailed.remove(projectId);
            return best;
        } catch (Exception e) {
            // Сбой сети не кэшируется: раньше он запоминался как «подходящей версии нет», и
            // «Починка» с галочкой предлагала выключить мод — в том числе во втором проходе
            // (найдено ревью Android-копии 18.10.2026).
            lookupFailed.add(projectId);
            return null;
        }
    }

    // ---------------------------------------------------------------

    private static boolean mentioned(String log, JsonObject proj, String projectId) {
        if (log == null || log.isEmpty()) return false;
        String l = norm(log);
        String slug = proj == null ? "" : norm(str(proj, "slug"));
        return slug.length() >= 4 && l.contains(slug) || l.contains(norm(projectId));
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean jsonHas(JsonArray arr, String value) {
        for (JsonElement e : arr) if (e.isJsonPrimitive() && value.equals(e.getAsString())) return true;
        return false;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    /** «0.5.10+mc1.20» против «0.5.9»: по числам слева направо; нечисловое — как 0. */
    static int compareVersions(String a, String b) {
        // Как в semver: «0.9.2-beta.2» МЕНЬШЕ «0.9.2». Без этого «>=0.9.2-beta.2» отвергал
        // релиз 0.9.2, и выбор между двумя Sodium снова шёл в пользу беты.
        String sa = stripBuild(a == null ? "" : a), sb = stripBuild(b == null ? "" : b);
        int da = sa.indexOf('-'), db = sb.indexOf('-');
        int core = compareNumbers(da < 0 ? sa : sa.substring(0, da), db < 0 ? sb : sb.substring(0, db));
        if (core != 0) return core;
        if (da < 0 && db < 0) return 0;
        if (da < 0) return 1;
        if (db < 0) return -1;
        return compareNumbers(sa.substring(da + 1), sb.substring(db + 1));
    }

    private static int compareNumbers(String a, String b) {
        String[] x = (a == null ? "" : a).split("[^0-9]+");
        String[] y = (b == null ? "" : b).split("[^0-9]+");
        int i = 0, j = 0;
        while (i < x.length && x[i].isEmpty()) i++;
        while (j < y.length && y[j].isEmpty()) j++;
        for (; i < x.length || j < y.length; i++, j++) {
            long p = i < x.length && !x[i].isEmpty() ? parse(x[i]) : 0;
            long q = j < y.length && !y[j].isEmpty() ? parse(y[j]) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.length() > 12 ? s.substring(0, 12) : s); } catch (NumberFormatException e) { return 0; }
    }
}
