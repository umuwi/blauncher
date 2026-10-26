package com.example.launcher.ui;

import com.example.launcher.service.vps.ServHostRent;
import com.example.launcher.service.vps.ServHostRent.Catalog;
import com.example.launcher.service.vps.ServHostRent.Cpu;
import com.example.launcher.service.vps.ServHostRent.Location;
import com.example.launcher.service.vps.ServHostRent.Plan;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Выбор VPS на вкладке «Купить сервер» (26.10.2026): локация → процессор → тариф, как в форме
 * заказа кабинета serv.host (45 дата-центров, у каждого свой прайс — ServHostRent.catalog).
 * Раньше на вкладке были две зашитые плитки (Ryzen-1 / Ryzen-2) и кнопка «на сайт», а выбирать
 * приходилось уже в кабинете. Теперь выбор здесь, а оплата — в браузере по кнопке (Host.pay):
 * данные карты в окно лаунчера не попадают.
 */
public final class RentPicker {

    public interface Host {
        String tr(String key);

        void runInBackground(Runnable task);

        /** «Оплатить»: plan == null — тарифы не загрузились, открыть кабинет без выбора. */
        void pay(Plan plan, Location location, Cpu cpu);
    }

    /** Группы плиток локаций — 27 плиток подряд читались сплошной стеной. */
    private static final Set<String> RUSSIA = Set.of("msk", "nsk");
    private static final Set<String> AMERICA = Set.of("us", "ca");
    private static final Set<String> FRESH = Set.of("by", "sk", "hu", "ro", "tr", "bg", "rs");

    private static final double PLAN_TILE_WIDTH = 160;
    private static final double PLAN_TILE_HEIGHT = 136;

    private final Host host;
    private final VBox root = new VBox(12);
    private final VBox locBox = new VBox(8);
    private final FlowPane cpuRow = new FlowPane(8, 8);
    private final VBox cpuBox = new VBox(6);
    /** Характеристики выбранного дата-центра: защита от DDoS, сеть, процессор, диск, IP. */
    private final FlowPane specRow = new FlowPane(8, 8);
    private final FlowPane planGrid = new FlowPane(10, 10);
    private final HBox statusRow = new HBox(10);
    private final Label statusLabel = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Hyperlink retryLink = new Hyperlink();
    private final Hyperlink siteLink = new Hyperlink();
    private final Label summary = new Label();
    private final Button payBtn = new Button();
    private final VBox content = new VBox(12);

    private Catalog catalog;
    private boolean loading;
    private Location location;
    private Cpu cpu;
    private Plan plan;

    public RentPicker(Host host) {
        this.host = host;
        root.getStyleClass().add("rent-picker");

        Label locHead = new Label(host.tr("rent_pick_location"));
        locHead.getStyleClass().add("select-label");
        Label cpuHead = new Label(host.tr("rent_pick_cpu"));
        cpuHead.getStyleClass().add("select-label");
        cpuBox.getChildren().addAll(cpuHead, cpuRow);
        Label planHead = new Label(host.tr("rent_pick_plan"));
        planHead.getStyleClass().add("select-label");

        summary.getStyleClass().add("rent-summary");
        summary.setWrapText(true);
        summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summary, Priority.ALWAYS);
        payBtn.setText(host.tr("rent_pay_btn"));
        payBtn.getStyleClass().add("rent-btn");
        payBtn.setMinWidth(Region.USE_PREF_SIZE);
        payBtn.setGraphic(new FontIcon("fth-external-link"));
        payBtn.setOnAction(e -> {
            if (plan != null) host.pay(plan, location, cpu);
        });
        HBox payRow = new HBox(14, summary, payBtn);
        payRow.setAlignment(Pos.CENTER_LEFT);
        payRow.getStyleClass().add("rent-pay-row");

        specRow.getStyleClass().add("rent-specs");
        content.getChildren().addAll(
                new VBox(6, locHead, locBox),
                cpuBox,
                specRow,
                new VBox(6, planHead, planGrid),
                payRow);

        spinner.setMaxSize(16, 16);
        statusLabel.getStyleClass().add("toggle-sub");
        statusLabel.setWrapText(true);
        retryLink.setText(host.tr("rent_retry"));
        retryLink.getStyleClass().add("all-builds-link");
        retryLink.setOnAction(e -> load());
        siteLink.setText(host.tr("rent_open_site_link"));
        siteLink.getStyleClass().add("all-builds-link");
        siteLink.setOnAction(e -> host.pay(null, null, null));
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.getChildren().addAll(spinner, statusLabel, retryLink, siteLink);

        root.getChildren().addAll(statusRow, content);
        show(content, false);
    }

    public Node node() {
        return root;
    }

    /** Первый заход на вкладку — читаем тарифы; потом показываем уже прочитанные. */
    public void loadIfNeeded() {
        if (catalog == null && !loading) load();
    }

    /** «Венгрия AMD Ryzen» — так дата-центр назван в выпадающем списке кабинета. */
    public String dcLabel(Location loc, Cpu c) {
        return host.tr("rent_c_" + loc.code()) + " " + c.family();
    }

    private void load() {
        loading = true;
        setStatus(host.tr("rent_loading"), true, false);
        host.runInBackground(() -> {
            try {
                Catalog c = ServHostRent.catalog();
                Platform.runLater(() -> {
                    loading = false;
                    catalog = c;
                    buildLocations();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    loading = false;
                    setStatus(host.tr("rent_load_fail"), false, true);
                });
            }
        });
    }

    private void setStatus(String text, boolean busy, boolean failed) {
        statusLabel.setText(text);
        show(spinner, busy);
        show(retryLink, failed);
        show(siteLink, failed);
        show(statusRow, true);
        show(content, false);
    }

    private void buildLocations() {
        locBox.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        Map<String, FlowPane> regions = new LinkedHashMap<>();
        for (String r : List.of("ru", "eu", "am", "new")) regions.put(r, new FlowPane(8, 8));
        ToggleButton first = null;
        for (Location loc : ServHostRent.LOCATIONS) {
            int from = minPrice(loc);
            if (from <= 0) continue; // в каталоге нет ни одного её дата-центра — не показываем
            ToggleButton tile = tile(Flags.of(loc.flag()), host.tr("rent_c_" + loc.code()),
                    host.tr("rent_price_from").replace("{price}", rub(from)));
            tile.setToggleGroup(group);
            tile.setUserData(loc);
            regions.get(region(loc.code())).getChildren().add(tile);
            if (first == null) first = tile;
        }
        if (first == null) {
            setStatus(host.tr("rent_load_fail"), false, true);
            return;
        }
        regions.forEach((r, pane) -> {
            if (pane.getChildren().isEmpty()) return;
            Label head = new Label(host.tr("rent_region_" + r));
            head.getStyleClass().add("rent-region");
            locBox.getChildren().addAll(head, pane);
        });
        keepOneSelected(group);
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) selectLocation((Location) b.getUserData());
        });
        show(statusRow, false);
        show(content, true);
        first.setSelected(true);
    }

    private static String region(String code) {
        if (RUSSIA.contains(code)) return "ru";
        if (AMERICA.contains(code)) return "am";
        if (FRESH.contains(code)) return "new";
        return "eu";
    }

    private void selectLocation(Location loc) {
        location = loc;
        cpuRow.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        ToggleButton first = null;
        for (Cpu c : loc.cpus()) {
            List<Plan> plans = catalog.plans().get(c.dc());
            if (plans == null || plans.isEmpty()) continue;
            String from = host.tr("rent_price_from").replace("{price}", rub(minPrice(plans)));
            // В подписи — процессор и частота из описания тарифа в кабинете («Ryzen 9 7950X до
            // 5,7 ГГц»), без него — модель со страницы тарифов; у новых стран там только «AMD Ryzen».
            String cpuText = spec(c).getOrDefault("cpu", c.model());
            String sub = cpuText.isEmpty() || cpuText.equals(c.family()) ? from : cpuText + " · " + from;
            ToggleButton tile = tile(null, c.family(), sub);
            tile.setToggleGroup(group);
            tile.setUserData(c);
            cpuRow.getChildren().add(tile);
            if (first == null) first = tile;
        }
        keepOneSelected(group);
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) selectCpu((Cpu) b.getUserData());
        });
        // Один процессор — выбирать нечего, строку прячем.
        show(cpuBox, cpuRow.getChildren().size() > 1);
        if (first != null) first.setSelected(true);
    }

    private void selectCpu(Cpu c) {
        cpu = c;
        buildSpecs(spec(c));
        planGrid.getChildren().clear();
        List<Plan> plans = catalog.plans().getOrDefault(c.dc(), List.of());
        ToggleGroup group = new ToggleGroup();
        ToggleButton preferred = null;
        for (Plan p : plans) {
            ToggleButton tile = planTile(p);
            tile.setToggleGroup(group);
            tile.setUserData(p);
            planGrid.getChildren().add(tile);
            // По умолчанию — 8 ГБ (моды или до 20 игроков), иначе первый от 4 ГБ: 2 ГБ хватает только
            // на ваниль для двоих, а новичок, взявший самый дешёвый, потом жалуется на лаги.
            if (preferred == null && p.ramGb() >= 4) preferred = tile;
            if (p.ramGb() == 8) preferred = tile;
        }
        keepOneSelected(group);
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) selectPlan((Plan) b.getUserData());
        });
        if (preferred == null && !planGrid.getChildren().isEmpty()) preferred = (ToggleButton) planGrid.getChildren().get(0);
        if (preferred != null) preferred.setSelected(true);
    }

    private void selectPlan(Plan p) {
        plan = p;
        summary.setText(host.tr("rent_summary")
                .replace("{plan}", p.name())
                .replace("{loc}", dcLabel(location, cpu))
                .replace("{price}", rub(p.priceRub())));
    }

    private ToggleButton planTile(Plan p) {
        Label ram = new Label(host.tr("rent_ram").replace("{n}", String.valueOf(p.ramGb())));
        ram.getStyleClass().add("rent-tile-ram");
        Label spec = new Label(host.tr("rent_spec")
                .replace("{cores}", String.valueOf(p.cores()))
                .replace("{disk}", String.valueOf(p.diskGb())));
        spec.getStyleClass().add("rent-tile-spec");
        Label price = new Label(rub(p.priceRub()) + host.tr("rent_per_month"));
        price.getStyleClass().add("rent-tile-price");
        Label fit = new Label(host.tr(fitKey(p.ramGb())));
        fit.getStyleClass().add("rent-tile-fit");
        fit.setWrapText(true);
        fit.setPrefWidth(PLAN_TILE_WIDTH - 24);
        VBox box = new VBox(2, ram, spec, price, fit);
        if (p.ramGb() == 8) {
            Label badge = new Label(host.tr("rent_recommended"));
            badge.getStyleClass().add("rent-badge");
            box.getChildren().add(0, badge);
        }
        ToggleButton t = new ToggleButton();
        t.setGraphic(box);
        t.getStyleClass().addAll("rent-tile", "rent-plan-tile");
        // Высота — фиксированная: перенос подписи внутри ToggleButton в FlowPane считал высоту при
        // ширине -1 и растягивал плитки на весь экран. Заодно все плитки ряда ровные.
        t.setPrefSize(PLAN_TILE_WIDTH, PLAN_TILE_HEIGHT);
        t.setMinSize(PLAN_TILE_WIDTH, PLAN_TILE_HEIGHT);
        t.setMaxSize(PLAN_TILE_WIDTH, PLAN_TILE_HEIGHT);
        t.setAlignment(Pos.TOP_LEFT);
        return t;
    }

    private Map<String, String> spec(Cpu c) {
        return catalog.specs().getOrDefault(c.dc(), Map.of());
    }

    /**
     * Значки характеристик дата-центра — то, что в кабинете написано мелким текстом в описании
     * каждого тарифа (26.10.2026 владелец попросил показать DDoS-защиту, скорость сети и т. п.).
     * Нет поля в каталоге — нет и значка: файл с сайта может прийти без характеристик.
     */
    private void buildSpecs(Map<String, String> sp) {
        specRow.getChildren().clear();
        String ddos = sp.get("ddos");
        if (ddos != null) {
            specRow.getChildren().add(specChip("fth-shield", host.tr("guard".equals(ddos) ? "rent_spec_ddos_guard" : "rent_spec_ddos_basic"),
                    "guard".equals(ddos)));
        }
        String net = sp.get("net");
        if (net != null && !net.isEmpty()) {
            String burst = sp.getOrDefault("burst", "");
            String text = burst.isEmpty() ? host.tr("rent_spec_net").replace("{net}", speed(net))
                    : host.tr("rent_spec_net_burst").replace("{net}", speed(net)).replace("{burst}", speed(burst));
            specRow.getChildren().add(specChip("fth-activity", text, false));
        }
        String cpuText = sp.get("cpu");
        if (cpuText != null && !cpuText.isEmpty()) {
            specRow.getChildren().add(specChip("fth-cpu", cpuText, false));
        }
        String disk = sp.getOrDefault("disk", "");
        String virt = sp.getOrDefault("virt", "");
        if (!disk.isEmpty() || !virt.isEmpty()) {
            String text = (disk.isEmpty() ? "" : host.tr("rent_spec_disk").replace("{disk}", disk))
                    + (!disk.isEmpty() && !virt.isEmpty() ? " · " : "")
                    + (virt.isEmpty() ? "" : host.tr("rent_spec_virt").replace("{virt}", virt));
            specRow.getChildren().add(specChip("fth-hard-drive", text, false));
        }
        String v4 = sp.getOrDefault("ipv4", "");
        if (!v4.isEmpty()) {
            String v6 = sp.getOrDefault("ipv6", "");
            String text = host.tr("rent_spec_ipv4").replace("{n}", v4)
                    + (v6.isEmpty() ? "" : host.tr("rent_spec_ipv6").replace("{prefix}", v6));
            specRow.getChildren().add(specChip("fth-globe", text, false));
        }
        String city = sp.getOrDefault("city", "");
        if (!city.isEmpty()) specRow.getChildren().add(specChip("fth-map-pin", city, false));
        show(specRow, !specRow.getChildren().isEmpty());
    }

    /** 250 → «250 Мбит/с», 1000 → «1 Гбит/с»; не число — как есть. */
    private String speed(String mbit) {
        try {
            int v = Integer.parseInt(mbit.trim());
            if (v >= 1000 && v % 1000 == 0) return host.tr("rent_gbit").replace("{n}", String.valueOf(v / 1000));
            return host.tr("rent_mbit").replace("{n}", String.valueOf(v));
        } catch (NumberFormatException e) {
            return mbit;
        }
    }

    private Node specChip(String icon, String text, boolean strong) {
        FontIcon i = new FontIcon(icon);
        i.setIconSize(13);
        Label l = new Label(text);
        HBox chip = new HBox(6, i, l);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("rent-spec");
        if (strong) chip.getStyleClass().add("strong");
        return chip;
    }

    /** Подсказка, на что хватит памяти — для Minecraft-сервера, а не для VPS вообще. */
    static String fitKey(int ramGb) {
        if (ramGb <= 2) return "rent_fit_tiny";
        if (ramGb <= 4) return "rent_fit_small";
        if (ramGb <= 8) return "rent_fit_mods";
        if (ramGb <= 16) return "rent_fit_big";
        return "rent_fit_huge";
    }

    private ToggleButton tile(Node icon, String title, String sub) {
        Label t = new Label(title);
        t.getStyleClass().add("rent-tile-title");
        Label s = new Label(sub);
        s.getStyleClass().add("rent-tile-sub");
        VBox text = new VBox(1, t, s);
        ToggleButton b = new ToggleButton();
        if (icon != null) {
            HBox row = new HBox(8, icon, text);
            row.setAlignment(Pos.CENTER_LEFT);
            b.setGraphic(row);
        } else {
            b.setGraphic(text);
        }
        b.getStyleClass().add("rent-tile");
        return b;
    }

    private int minPrice(Location loc) {
        int min = 0;
        for (Cpu c : loc.cpus()) {
            List<Plan> plans = catalog.plans().get(c.dc());
            if (plans == null || plans.isEmpty()) continue;
            int m = minPrice(plans);
            if (min == 0 || m < min) min = m;
        }
        return min;
    }

    private static int minPrice(List<Plan> plans) {
        int min = Integer.MAX_VALUE;
        for (Plan p : plans) min = Math.min(min, p.priceRub());
        return min;
    }

    /** Повторный щелчок по выбранной плитке не должен оставлять «ничего не выбрано». */
    private static void keepOneSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                Toggle back = was;
                Platform.runLater(() -> group.selectToggle(back));
            }
        });
    }

    /**
     * 1700 → «1 700 руб.». Знака ₽ нет во встроенном шрифте Inter — на его месте была пустота,
     * поэтому валюта словом (rent_rub). Пробелы неразрывные, чтобы цена не рвалась на две строки.
     */
    private String rub(int price) {
        return String.format(java.util.Locale.ROOT, "%,d", price).replace(',', ' ') + " " + host.tr("rent_rub");
    }

    private static void show(Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }
}
