package com.example.launcher.controller;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal i18n dictionary for the most visible, frequently-updated strings that are built
 * programmatically in MainController (statuses, button labels, screen titles) — NOT a full
 * translation of every string in main.fxml, which is handled separately via FXML's own %key
 * mechanism (see MainApp's ResourceBundle wiring and i18n/strings_ru.properties /
 * strings_en.properties for that). This class covers only the subset MainController needs to
 * update live, with no app restart, when the language toggle is used — see
 * MainController.refreshLanguageTexts().
 *
 * Split out of MainController on its own since it's pure static data with no dependency on any
 * controller field — keeping ~90 dict.put(...) lines inline in the controller made that file
 * harder to scan for its actual logic.
 */
final class Strings {

    private Strings() {
    }

    private static final Map<String, Map<String, String>> DICTIONARY = buildDictionary();

    /**
     * Looks up a UI string in the given language: this dictionary first, then the same
     * strings_*.properties files main.fxml reads, Russian as the fallback, the key itself last.
     *
     * <p>The properties step exists because code-built screens (tools, the skin editor, the item
     * studio) use keys that only live there, and a key that silently came back as itself showed
     * up on screen as "tool_skin_title".
     */
    static String tr(String key, String language) {
        Map<String, String> entry = DICTIONARY.get(key);
        if (entry != null) {
            String value = entry.get(language);
            return value != null ? value : entry.getOrDefault("ru", key);
        }
        String value = bundle(language).get(key);
        if (value == null && !"ru".equals(language)) value = bundle("ru").get(key);
        return value != null ? value : key;
    }

    private static final Map<String, Map<String, String>> BUNDLES = new java.util.concurrent.ConcurrentHashMap<>();

    private static Map<String, String> bundle(String language) {
        String lang = "en".equals(language) ? "en" : "ru";
        return BUNDLES.computeIfAbsent(lang, l -> {
            Map<String, String> map = new HashMap<>();
            try (var in = Strings.class.getResourceAsStream("/i18n/strings_" + l + ".properties")) {
                if (in == null) return map;
                java.util.Properties props = new java.util.Properties();
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                for (String name : props.stringPropertyNames()) map.put(name, props.getProperty(name));
            } catch (java.io.IOException ignored) {
                // no file: the key itself is shown, as before
            }
            return map;
        });
    }

    private static Map<String, Map<String, String>> buildDictionary() {
        Map<String, Map<String, String>> dict = new HashMap<>();
        dict.put("account_title", Map.of("ru", "Аккаунт", "en", "Account"));
        dict.put("add_plugin_title", Map.of("ru", "Добавить плагин", "en", "Add plugin"));
        dict.put("add_server_btn", Map.of("ru", "+ Новый сервер", "en", "+ New server"));
        dict.put("agent_check_btn", Map.of("ru", "Проверить подключение", "en", "Check connection"));
        dict.put("agent_install_label", Map.of("ru", "Выполните эту команду на сервере по SSH - один раз, займёт минуту:", "en", "Run this command on the server over SSH — one time, takes a minute:"));
        dict.put("agent_not_connected", Map.of("ru", "Агент не подключен", "en", "Agent not connected"));
        dict.put("agent_panel_title", Map.of("ru", "Подключение агента", "en", "Agent connection"));
        dict.put("agent_checking", Map.of("ru", "Проверка подключения...", "en", "Checking connection..."));
        dict.put("back_btn", Map.of("ru", "Назад", "en", "Back"));
        dict.put("build_create_title", Map.of("ru", "Новая сборка", "en", "New build"));
        dict.put("build_name_label", Map.of("ru", "Название сборки", "en", "Build name"));
        dict.put("builds_sub", Map.of("ru", "Твои наборы модов. Нажми на сборку, чтобы играть в неё", "en", "Your mod sets. Click a build to play it"));
        dict.put("builds_title", Map.of("ru", "Сборки", "en", "Builds"));
        dict.put("friends_remove_btn", Map.of("ru", "Удалить из друзей", "en", "Remove friend"));
        dict.put("nav_friends", Map.of("ru", "Друзья", "en", "Friends"));
        dict.put("friends_sub", Map.of("ru", "Друзья по коду и комнаты для совместной игры", "en", "Friends by code and rooms to play together"));
        dict.put("palette_saved", Map.of("ru", "Палитра сохранена", "en", "Palette saved"));
        dict.put("zt_install_btn", Map.of("ru", "Установить движок", "en", "Install engine"));
        dict.put("zt_my_address", Map.of("ru", "Адрес для друзей:", "en", "Address for friends:"));
        dict.put("friends_address_label", Map.of("ru", "Адрес для друзей", "en", "Address for friends"));
        dict.put("clear_btn", Map.of("ru", "Очистить", "en", "Clear"));
        dict.put("commands_modal_title", Map.of("ru", "Команды сервера", "en", "Server commands"));
        dict.put("commands_no_results", Map.of("ru", "Сервер не вернул список команд", "en", "The server didn't return any commands"));
        dict.put("commands_copied_prefix", Map.of("ru", "Скопировано: ", "en", "Copied: "));
        dict.put("commands_fetching_log", Map.of("ru", "Запрашиваю список команд (help)...", "en", "Requesting the command list (help)..."));
        dict.put("commands_retry_log", Map.of("ru", "\"help\" ничего не вернул (вероятно, перехвачен EssentialsX) — пробую \"?\"...", "en", "\"help\" returned nothing (likely intercepted by EssentialsX) — trying \"?\"..."));
        dict.put("close_launcher_label", Map.of("ru", "Закрывать лаунчер при запуске игры", "en", "Close launcher when the game starts"));
        dict.put("close_launcher_sub", Map.of("ru", "Освобождает память во время игры", "en", "Frees up memory while playing"));
        dict.put("copy_btn", Map.of("ru", "Копировать", "en", "Copy"));
        dict.put("create_btn", Map.of("ru", "Создать", "en", "Create"));
        dict.put("current_build_title", Map.of("ru", "Сейчас выбрано", "en", "Currently selected"));
        dict.put("empty_builds", Map.of("ru", "Пока нет сборок - создай новую или импортируй по ссылке", "en", "No builds yet — create one or import via link"));
        dict.put("export_btn", Map.of("ru", "Экспорт", "en", "Export"));
        dict.put("export_link_label", Map.of("ru", "Ссылка для друзей", "en", "Link for friends"));
        // --- Друзья по коду: всё, что панель друзей пишет в статус/тосты, плюс
        //     расшифровки коротких кодов ошибок, которые возвращает friends.php. ---
        dict.put("friends_status_syncing", Map.of("ru", "Обновляем…", "en", "Refreshing…"));
        dict.put("friends_refresh_btn", Map.of("ru", "Обновить", "en", "Refresh"));
        dict.put("friends_refresh_wait", Map.of("ru", "Подожди ещё", "en", "Wait another"));
        dict.put("friends_seconds_short", Map.of("ru", "с", "en", "s"));
        // --- Приглашения в комнату ZeroTier ---
        dict.put("friends_invite_btn", Map.of("ru", "Пригласить", "en", "Invite"));
        dict.put("friends_invite_pick_title", Map.of("ru", "Пригласить в комнату:", "en", "Invite to a room:"));
        dict.put("friends_invite_no_rooms", Map.of("ru", "Пока нет комнат, куда можно позвать. Создай комнату в блоке «Играть вместе» выше — после этого друг приглашается в один клик.", "en", "No rooms to invite anyone into yet. Create one in the Play together block above — after that inviting a friend is one click."));
        dict.put("friends_invite_zt_off", Map.of("ru", "Сначала включи ZeroTier в блоке «Играть вместе» — приглашать можно только в комнату, в которой состоишь сам.", "en", "Turn ZeroTier on in the Play together block first — you can only invite somebody into a room you are in yourself."));
        dict.put("friends_invite_room_private", Map.of("ru", "закрытая — вход по одобрению", "en", "closed — entry by approval"));
        dict.put("friends_invite_room_public", Map.of("ru", "открытая", "en", "open"));
        dict.put("friends_invite_row_action", Map.of("ru", "зовёт в комнату", "en", "invites you to"));
        dict.put("friends_invite_join", Map.of("ru", "Войти", "en", "Join"));
        dict.put("friends_invite_decline", Map.of("ru", "Отклонить", "en", "Decline"));
        dict.put("friends_invite_sent", Map.of("ru", "Приглашение отправлено: ", "en", "Invitation sent: "));
        dict.put("friends_invite_arrived", Map.of("ru", "Приглашение в комнату от", "en", "Room invitation from"));
        dict.put("friends_invite_joined", Map.of("ru", "Вошёл в комнату", "en", "Joined the room"));
        dict.put("friends_invite_join_failed", Map.of("ru", "Не удалось войти в комнату:", "en", "Could not join the room:"));
        dict.put("friends_invite_accepted", Map.of("ru", "Принял приглашение: ", "en", "Accepted your invitation: "));
        dict.put("friends_invite_declined", Map.of("ru", "Отклонил приглашение: ", "en", "Declined your invitation: "));
        dict.put("friends_err_not_friend", Map.of("ru", "Приглашать можно только друзей", "en", "Only friends can be invited"));
        dict.put("friends_err_bad_network", Map.of("ru", "Это не похоже на ID комнаты", "en", "That does not look like a room ID"));
        dict.put("friends_err_too_many_invites", Map.of("ru", "Слишком много непрочитанных приглашений", "en", "Too many unanswered invitations"));
        dict.put("friends_status_offline", Map.of("ru", "Сервер друзей недоступен — показан последний известный список", "en", "Friends server unreachable — showing the last known list"));
        dict.put("friends_loading", Map.of("ru", "Загружаем список друзей…", "en", "Loading your friends…"));
        dict.put("vps_card_has", Map.of(
                "ru", "Подключено VPS: {n} — открыть можно в «Мои серверы» ниже. Локальный сервер переносится кнопкой «На VPS» на его экране.",
                "en", "Connected VPS: {n} — open them under \"My servers\" below. Move a local server with the \"To VPS\" button on its screen."));
        dict.put("vps_card_move_hint", Map.of(
                "ru", "Уже есть локальный сервер? Подключите VPS — и перенесите на него мир, плагины и моды на вкладке «Перенести с ПК».",
                "en", "Already have a local server? Connect a VPS and move its world, plugins and mods there on the \"Move from PC\" tab."));
        dict.put("friends_empty", Map.of("ru", "Пока никого нет — добавь друга по коду выше", "en", "Nobody here yet — add a friend by code above"));
        dict.put("friends_online", Map.of("ru", "в сети", "en", "online"));
        dict.put("friends_offline", Map.of("ru", "не в сети", "en", "offline"));
        dict.put("friends_no_about", Map.of("ru", "Друг пока ничего о себе не написал", "en", "This friend hasn't written anything about themselves yet"));
        dict.put("friends_my_no_about", Map.of("ru", "Ты пока ничего о себе не написал", "en", "You haven't written anything about yourself yet"));
        dict.put("friends_you", Map.of("ru", "это ты", "en", "this is you"));
        dict.put("friends_no_skin", Map.of("ru", "?", "en", "?"));
        dict.put("friends_added", Map.of("ru", "Друг добавлен: ", "en", "Friend added: "));
        dict.put("friends_already_friend", Map.of("ru", "Этот игрок уже в друзьях: ", "en", "Already on your friends list: "));
        dict.put("friends_removed", Map.of("ru", "Удалён из друзей: ", "en", "Removed from friends: "));
        dict.put("friends_code_copied", Map.of("ru", "Код дружбы скопирован", "en", "Friend code copied"));
        dict.put("friends_about_saved", Map.of("ru", "Сохранено — этот текст видят все твои друзья", "en", "Saved — all of your friends see this text"));
        dict.put("friends_about_too_long", Map.of("ru", "Слишком длинно — максимум 300 символов", "en", "Too long — 300 characters at most"));
        dict.put("friends_remove_confirm", Map.of("ru", "Точно удалить?", "en", "Remove for sure?"));
        dict.put("friends_code_pending", Map.of("ru", "Получаем код…", "en", "Getting your code…"));
        dict.put("friends_err_offline", Map.of("ru", "Сервер друзей недоступен — проверь интернет и попробуй ещё раз", "en", "Friends server unreachable — check your connection and try again"));
        dict.put("friends_err_not_found", Map.of("ru", "Игрок с таким кодом не найден — проверь код", "en", "No player with that code — check it again"));
        dict.put("friends_err_self", Map.of("ru", "Это твой собственный код", "en", "That is your own code"));
        dict.put("friends_err_bad_code", Map.of("ru", "Код должен быть вида BL-XXXX-XXXX", "en", "A code looks like BL-XXXX-XXXX"));
        dict.put("friends_err_unknown_player", Map.of("ru", "Сервер не узнал этот лаунчер — перезапусти лаунчер", "en", "The server doesn't recognise this launcher — restart it"));
        dict.put("friends_err_rate_limited", Map.of("ru", "Слишком много попыток — подожди минуту", "en", "Too many attempts — wait a minute"));
        dict.put("friends_err_too_many_friends", Map.of("ru", "Слишком много друзей — удали кого-нибудь", "en", "Too many friends — remove someone first"));
        dict.put("friends_err_nick", Map.of("ru", "Сначала задай имя игрока на главном экране", "en", "Set your player name on the home screen first"));
        dict.put("friends_err_unknown", Map.of("ru", "Не получилось — попробуй ещё раз", "en", "That didn't work — try again"));
        dict.put("friends_err_title", Map.of("ru", "Друзья: ", "en", "Friends: "));
        dict.put("game_console_title", Map.of("ru", "Консоль игры", "en", "Game console"));
        dict.put("home_sub", Map.of("ru", "Запусти без модов или выбери одну из популярных сборок", "en", "Launch without mods or pick one of your popular builds"));
        dict.put("import_btn", Map.of("ru", "Импорт", "en", "Import"));
        dict.put("import_confirm_btn", Map.of("ru", "Импортировать", "en", "Import"));
        dict.put("import_paste_label", Map.of("ru", "Вставь ссылку, которую прислали", "en", "Paste the link you were sent"));
        dict.put("label_build", Map.of("ru", "СБОРКА", "en", "BUILD"));
        dict.put("label_loader", Map.of("ru", "Загрузчик", "en", "Loader"));
        dict.put("label_version", Map.of("ru", "Версия Minecraft", "en", "Minecraft version"));
        dict.put("language_sub", Map.of("ru", "Меняет язык всего лаунчера", "en", "Changes the whole launcher's language"));
        dict.put("language_title", Map.of("ru", "Язык интерфейса", "en", "Interface language"));
        dict.put("loader_version_label", Map.of("ru", "Версия загрузчика", "en", "Loader version"));
        dict.put("local_server_title", Map.of("ru", "Локальный сервер", "en", "Local server"));
        dict.put("login_code_hint", Map.of("ru", "В открывшемся окне введите этот код:", "en", "Enter this code in the window that opened:"));
        dict.put("microsoft_login_btn", Map.of("ru", "Войти через Microsoft", "en", "Sign in with Microsoft"));
        dict.put("license_yes", Map.of("ru", "Есть лицензия", "en", "Licensed"));
        dict.put("license_no", Map.of("ru", "Нет лицензии", "en", "No license"));
        // --- Аккаунт Microsoft у каждого профиля свой (см. switchAccountToCurrentProfile) ---
        dict.put("account_saved", Map.of("ru", "аккаунт сохранён", "en", "account saved"));
        dict.put("account_prefix", Map.of("ru", "аккаунт:", "en", "account:"));
        dict.put("account_none", Map.of("ru", "вход не выполнен", "en", "not signed in"));
        dict.put("account_checking", Map.of("ru", "проверяем вход…", "en", "checking sign-in…"));
        dict.put("mods_sub", Map.of("ru", "Найди мод и добавь его в любую свою сборку", "en", "Find a mod and add it to any of your builds"));
        dict.put("mods_title", Map.of("ru", "Моды", "en", "Mods"));
        dict.put("my_servers_title", Map.of("ru", "Мои серверы", "en", "My servers"));
        dict.put("nav_builds_l1", Map.of("ru", "Сборки", "en", "Builds"));
        dict.put("nav_builds_l2", Map.of("ru", "и сервера", "en", "and Servers"));
        dict.put("nav_home", Map.of("ru", "Главная", "en", "Home"));
        dict.put("network_sub", Map.of("ru", "Связь с другими серверами через прокси", "en", "Connection to other servers via proxy"));
        dict.put("network_title", Map.of("ru", "Сеть", "en", "Network"));
        dict.put("offline_mode_label", Map.of("ru", "Играть офлайн", "en", "Play offline"));
        dict.put("offline_mode_sub", Map.of("ru", "Запуск без сети, даже если выполнен вход в Microsoft — только для уже скачанных версий", "en", "Launch with no network, even if signed into Microsoft — only for already-downloaded versions"));
        dict.put("play_btn", Map.of("ru", "Играть", "en", "Play"));
        dict.put("play_hint", Map.of("ru", "Готово к запуску", "en", "Ready to launch"));
        dict.put("plugins_btn", Map.of("ru", "Скачать плагины", "en", "Download plugins"));
        dict.put("profiles_btn", Map.of("ru", "Профили", "en", "Profiles"));
        dict.put("profiles_title", Map.of("ru", "Профили", "en", "Profiles"));
        dict.put("profiles_empty", Map.of("ru", "Нет сохранённых профилей", "en", "No saved profiles"));
        dict.put("add_profile_btn", Map.of("ru", "Добавить профиль", "en", "Add profile"));
        dict.put("profile_new_title", Map.of("ru", "Новый профиль", "en", "New profile"));
        dict.put("profile_edit_title", Map.of("ru", "Редактировать профиль", "en", "Edit profile"));
        dict.put("profile_name_label", Map.of("ru", "Никнейм", "en", "Nickname"));
        dict.put("profile_name_prompt", Map.of("ru", "Введите никнейм", "en", "Enter nickname"));
        dict.put("profile_name_max_warning", Map.of("ru", "Максимум 16 символов", "en", "Maximum 16 characters"));
        dict.put("profile_name_invalid_chars", Map.of("ru", "В нике нет допустимых символов Minecraft", "en", "No valid Minecraft characters in the nickname"));
        dict.put("profile_warning_title", Map.of("ru", "Как работают скины и плащи", "en", "How skins and capes work"));
        dict.put("profile_warning_body", Map.of("ru", "Загрузи файл, нарисуй во вкладке «Инструменты» или скачай с сайта по нику игрока (ники с нужным плащом ищи на NameMC).", "en", "Upload a file, paint one in Tools, or download it from a site by a player's nickname (find nicknames with the cape you want on NameMC)."));
        dict.put("profile_warning_capes_howto", Map.of("ru", "Как понять, какой плащ у игрока: открой NameMC → раздел «Плащи», выбери игрока с нужным плащом и скопируй его ник без эмодзи и лишних символов (только буквы, цифры и _). Вставь ник в поле ниже и выбери сайт, с которого брать плащ. Если плащ обновился, а в игре старый — перезапусти лаунчер или игру, кэш будет очищен.", "en", "To find out which cape a player has: open NameMC → Capes section, pick a player with the cape you want and copy their nickname without emojis or extra symbols (only letters, digits and _). Paste the nickname into the field below and choose the site to take the cape from. If the cape was updated but the game still shows the old one, restart the launcher or the game — the cache will be cleared."));
        dict.put("profile_warning_mods", Map.of("ru", "В сборках, которые запрещают скачивание или использование сторонних модов, игра может не запуститься, либо скин/плащ не будет работать.", "en", "In builds that forbid downloading or using third-party mods, the game may fail to launch, or the skin/cape may not work."));
        dict.put("profile_skin_label", Map.of("ru", "Скин", "en", "Skin"));
        dict.put("profile_skin_upload_btn", Map.of("ru", "Загрузить скин", "en", "Upload skin"));
        dict.put("profile_cape_label", Map.of("ru", "Плащ", "en", "Cape"));
        dict.put("profile_cape_upload_btn", Map.of("ru", "Загрузить плащ", "en", "Upload cape"));
        dict.put("profile_cape_popular_prompt", Map.of("ru", "Популярные ники с плащами", "en", "Popular nicknames with capes"));
        // --- Источники скинов и плащей: строка под названием сайта говорит, что он отдаёт,
        //     и то, чем отвечает загрузка. Каждый сайт здесь умеет отдать файл по нику. ---
        dict.put("profile_source_note_mojang", Map.of("ru", "официальный скин", "en", "official skin"));
        dict.put("profile_source_note_elyby", Map.of("ru", "любые скины и slim", "en", "custom and slim"));
        dict.put("profile_source_note_optifine", Map.of("ru", "плащ OptiFine", "en", "the OptiFine cape"));
        dict.put("profile_source_note_labymod", Map.of("ru", "плащ LabyMod", "en", "the LabyMod cape"));
        dict.put("profile_source_note_tlauncher", Map.of("ru", "плащ TLauncher", "en", "the TLauncher cape"));
        dict.put("profile_source_note_minecraftcapes", Map.of("ru", "плащ MinecraftCapes", "en", "the MinecraftCapes cape"));
        dict.put("profile_source_note_skinmc", Map.of("ru", "плащ с SkinMC", "en", "the cape from SkinMC"));
        dict.put("profile_source_note_minecraft", Map.of("ru", "официальный плащ", "en", "official cape"));
        dict.put("profile_source_note_capesdev", Map.of("ru", "любой плащ игрока", "en", "any cape"));
        dict.put("profile_source_need_nick", Map.of("ru", "Сначала укажи ник игрока", "en", "Enter a player nickname first"));
        dict.put("profile_source_pulling", Map.of("ru", "Скачиваю:", "en", "Downloading:"));
        dict.put("profile_source_failed", Map.of("ru", "Не удалось скачать", "en", "Download failed"));
        dict.put("profile_source_bad_url", Map.of("ru", "Нужна прямая ссылка на картинку (http или https)", "en", "Needs a direct image link (http or https)"));
        dict.put("profile_source_bad_image", Map.of("ru", "по ссылке не картинка", "en", "that link is not an image"));
        dict.put("profile_source_url_label", Map.of("ru", "Ссылка", "en", "Link"));
        dict.put("profile_delete_btn", Map.of("ru", "Удалить профиль", "en", "Delete profile"));
        dict.put("save_btn", Map.of("ru", "Сохранить", "en", "Save"));
        dict.put("cancel_btn", Map.of("ru", "Отмена", "en", "Cancel"));
        dict.put("ram_title", Map.of("ru", "Память (RAM)", "en", "Memory (RAM)"));
        dict.put("recommended_ram_btn", Map.of("ru", "Рекомендуемое", "en", "Recommended"));
        dict.put("save_log_btn", Map.of("ru", "Сохранить лог", "en", "Save log"));
        dict.put("server_add_plugin_btn", Map.of("ru", "+ Добавить плагин", "en", "+ Add plugin"));
        dict.put("server_must_run_for_commands", Map.of("ru", "Сервер должен быть запущен, чтобы получить список команд", "en", "The server must be running to fetch the command list"));
        dict.put("server_advanced_btn", Map.of("ru", "Расширенные настройки", "en", "Advanced settings"));
        dict.put("server_attach_hub_label", Map.of("ru", "Подключить к хабу", "en", "Attach to a hub"));
        dict.put("server_console_label", Map.of("ru", "Консоль сервера", "en", "Server console"));
        dict.put("server_console_send", Map.of("ru", "Отправить", "en", "Send"));
        dict.put("server_create_title", Map.of("ru", "Новый сервер", "en", "New server"));
        dict.put("server_host_label", Map.of("ru", "Адрес (host)", "en", "Address (host)"));
        dict.put("server_is_hub_label", Map.of("ru", "Это хаб (прокси-сервер)", "en", "This is a hub (proxy server)"));
        dict.put("server_is_hub_sub", Map.of("ru", "Другие серверы смогут подключаться к нему как сателлиты", "en", "Other servers can connect to it as satellites"));
        dict.put("server_mc_port_label", Map.of("ru", "Порт Minecraft", "en", "Minecraft port"));
        dict.put("server_name_label", Map.of("ru", "Название сервера", "en", "Server name"));
        dict.put("server_plugins_title", Map.of("ru", "Плагины", "en", "Plugins"));
        dict.put("server_port_label", Map.of("ru", "Порт", "en", "Port"));
        dict.put("server_setup_downloading", Map.of("ru", "Скачивание ядра сервера…", "en", "Downloading server core…"));
        dict.put("server_monitor_title", Map.of("ru", "Мониторинг серверов", "en", "Server monitoring"));
        dict.put("server_monitor_sub", Map.of("ru", "Введи адрес любого Minecraft-сервера (IP:порт) — лаунчер покажет онлайн, игроков и задержку", "en", "Enter any Minecraft server address (IP:port) — the launcher shows online status, players and ping"));
        dict.put("server_monitor_add_btn", Map.of("ru", "Добавить сервер", "en", "Add server"));
        dict.put("server_monitor_placeholder", Map.of("ru", "IP или IP:порт (например play.example.com:25565)", "en", "IP or IP:port (e.g. play.example.com:25565)"));
        dict.put("server_monitor_add", Map.of("ru", "Добавить", "en", "Add"));
        dict.put("server_monitor_offline", Map.of("ru", "оффлайн", "en", "offline"));
        dict.put("server_monitor_ping", Map.of("ru", "Ping", "en", "Ping"));
        dict.put("server_monitor_players", Map.of("ru", "Игроки", "en", "Players"));
        dict.put("server_monitor_version", Map.of("ru", "Версия", "en", "Version"));
        dict.put("server_monitor_empty", Map.of("ru", "Пока нет серверов в мониторинге — добавь первый", "en", "No servers in monitoring yet — add the first one"));
        dict.put("server_monitor_error", Map.of("ru", "Не удалось получить данные", "en", "Failed to fetch data"));
        dict.put("server_monitor_remove", Map.of("ru", "Убрать", "en", "Remove"));
        dict.put("server_sub", Map.of("ru", "Запуск локального или подключение к внешнему серверу", "en", "Run a local server or connect to an external one"));
        dict.put("server_summary_zero", Map.of("ru", "Запущено: 0 - Остановлено: 0", "en", "Running: 0 · Stopped: 0"));
        dict.put("server_title", Map.of("ru", "Сервер", "en", "Server"));
        dict.put("server_type_local", Map.of("ru", "Локальный", "en", "Local"));
        dict.put("server_type_vps", Map.of("ru", "VPS / внешний", "en", "VPS / external"));
        dict.put("servers_title", Map.of("ru", "Серверы", "en", "Servers"));
        dict.put("settings_sub", Map.of("ru", "Применяются ко всем сборкам", "en", "Apply to every build"));
        dict.put("settings_title", Map.of("ru", "Настройки", "en", "Settings"));
        dict.put("share_title", Map.of("ru", "Поделиться сборкой", "en", "Share build"));
        dict.put("show_snapshots_label", Map.of("ru", "Показывать снапшоты", "en", "Show snapshots"));
        dict.put("show_snapshots_sub", Map.of("ru", "Еженедельные тестовые сборки Minecraft в списках версий", "en", "Weekly Minecraft development builds in version lists"));
        dict.put("start_btn", Map.of("ru", "Запустить", "en", "Start"));
        dict.put("status_not_running", Map.of("ru", "не запущен", "en", "not running"));
        dict.put("status_running", Map.of("ru", "запущен", "en", "running"));
        dict.put("status_starting", Map.of("ru", "Запускается...", "en", "Starting..."));
        dict.put("status_stopping", Map.of("ru", "Останавливается...", "en", "Stopping..."));
        dict.put("stop_btn", Map.of("ru", "Остановить", "en", "Stop"));
        dict.put("theme_black", Map.of("ru", "Чёрная", "en", "Black"));
        dict.put("theme_title", Map.of("ru", "Тема оформления", "en", "Theme"));
        dict.put("theme_violet", Map.of("ru", "Тёмная", "en", "Dark"));
        dict.put("theme_white", Map.of("ru", "Светлая", "en", "Light"));
        dict.put("zt_enable_btn", Map.of("ru", "Включить", "en", "Enable"));
        dict.put("zt_disable_btn", Map.of("ru", "Выключить", "en", "Disable"));
        dict.put("zt_start_service_btn", Map.of("ru", "Запустить службу ZeroTier", "en", "Start ZeroTier service"));
        dict.put("zt_status_not_installed", Map.of("ru", "Движок сети не установлен — друзья смогут заходить после установки", "en", "The network engine is not installed - friends can join once it is"));
        dict.put("zt_status_service_down", Map.of("ru", "Движок сети установлен, но служба остановлена — запустите её", "en", "The network engine is installed but its service is stopped - start it"));
        return dict;
    }
}
