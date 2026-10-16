package com.example.launcher.ui.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The scene as one {@code /summon} for a command block.
 *
 * <p>Same shape the popular web editors produce, because it is the one that survives being pasted
 * into a command block: an empty {@code block_display} at the given position carries every object
 * as a passenger, and each passenger's {@code transformation} places it relative to that root
 * (a display entity's passengers ride at its own origin, with no offset). All of them carry the
 * scene's tag, so one {@code /kill} takes the whole scene away again.
 *
 * <p>Display entities exist since 1.19.4. Item stacks changed in 1.20.5 ({@code Count:1b} became
 * {@code count:1}); that is the only difference between the two targets.
 */
public final class StudioCommand {

    /** A command block takes at most this many characters. */
    public static final int COMMAND_BLOCK_LIMIT = 32500;

    public enum Kind { BLOCK, ITEM }

    public enum Target {
        MODERN("1.20.5+ · 26.x"),
        LEGACY("1.19.4 – 1.20.4");

        public final String label;

        Target(String label) {
            this.label = label;
        }
    }

    /** One placed object. Position is its centre, rotation is Euler degrees applied X, then Y, then Z. */
    public static final class Item {
        public Kind kind = Kind.BLOCK;
        public String id = "stone";
        public Map<String, String> properties = new LinkedHashMap<>();
        public double x, y, z;
        public double rx, ry, rz;
        public double sx = 1, sy = 1, sz = 1;
        /** item_display's display context: none, fixed, ground, head, gui... */
        public String itemDisplay = "none";
        /** Lit as if in full daylight wherever it stands (a display in shade otherwise turns dark). */
        public boolean fullBright;
        /** The glowing outline mobs get from the Glowing effect, in {@link #glowColor}. */
        public boolean glow;
        public String glowColor = "#FFFFFF";

        public Item copy() {
            Item c = new Item();
            c.kind = kind;
            c.id = id;
            c.properties = new LinkedHashMap<>(properties);
            c.x = x; c.y = y; c.z = z;
            c.rx = rx; c.ry = ry; c.rz = rz;
            c.sx = sx; c.sy = sy; c.sz = sz;
            c.itemDisplay = itemDisplay;
            c.fullBright = fullBright;
            c.glow = glow;
            c.glowColor = glowColor;
            return c;
        }
    }

    private StudioCommand() {}

    /**
     * @param origin where the root is summoned, e.g. "~-0.5 ~1 ~-0.5"
     * @param tag    the scene's own tag, for the matching {@link #kill}
     */
    public static String summon(List<Item> items, Target target, String origin, String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append("summon minecraft:block_display ").append(origin.isBlank() ? "~ ~ ~" : origin.trim())
          .append(" {Tags:[\"blstudio\",\"").append(tag).append("\"],Passengers:[");
        List<String> passengers = new ArrayList<>();
        for (Item item : items) passengers.add(passenger(item, target, tag));
        sb.append(String.join(",", passengers));
        sb.append("]}");
        return sb.toString();
    }

    public static String kill(String tag) {
        return "kill @e[tag=" + tag + "]";
    }

    /**
     * Blocks the game draws with code instead of a block model: beds, chests, signs, banners,
     * heads, shulker boxes... A block_display of one of them is spawned but stays invisible, so
     * the command carries them as item_display of the same thing, which the game does draw.
     */
    public static boolean drawnAsItem(String id) {
        if (id.equals("piston_head")) return false;
        return id.endsWith("_bed") || id.endsWith("chest") || id.endsWith("_sign") || id.endsWith("_banner")
                || id.endsWith("_head") || id.endsWith("_skull") || id.endsWith("shulker_box")
                || id.equals("decorated_pot") || id.equals("conduit") || id.equals("bell")
                || id.equals("creeper_head") || id.equals("dragon_head") || id.endsWith("_copper_golem_statue")
                || id.equals("copper_golem_statue");
    }

    /** The item a wall-mounted block variant is placed from: oak_wall_sign -> oak_sign. */
    public static String itemFor(String id) {
        return id.replace("_wall_hanging_sign", "_hanging_sign").replace("_wall_sign", "_sign")
                .replace("_wall_banner", "_banner").replace("_wall_head", "_head").replace("_wall_skull", "_skull");
    }

    private static String passenger(Item item, Target target, String tag) {
        if (item.kind == Kind.BLOCK && drawnAsItem(item.id)) {
            Item asItem = item.copy();
            asItem.kind = Kind.ITEM;
            asItem.id = itemFor(item.id);
            return passenger(asItem, target, tag);
        }
        StringBuilder sb = new StringBuilder("{");
        if (item.kind == Kind.BLOCK) {
            // 26.3 renamed the block state's keys to id/properties and rejects a state without
            // "id", dropping the block; older versions read Name/Properties. Each ignores the
            // other's keys (checked on 1.21.11 and 26.4-snapshot-2 servers), so both go in and
            // one command works on either side of the change.
            String block = "\"minecraft:" + item.id + '"';
            sb.append("id:\"minecraft:block_display\",block_state:{Name:").append(block).append(",id:").append(block);
            if (!item.properties.isEmpty()) {
                List<String> props = new ArrayList<>();
                for (var p : item.properties.entrySet()) props.add(p.getKey() + ":\"" + p.getValue() + "\"");
                String joined = String.join(",", props);
                sb.append(",Properties:{").append(joined).append("},properties:{").append(joined).append('}');
            }
            sb.append('}');
        } else {
            sb.append("id:\"minecraft:item_display\",item:{id:\"minecraft:").append(item.id).append('"')
              .append(target == Target.MODERN ? ",count:1}" : ",Count:1b}");
            if (!"none".equals(item.itemDisplay)) sb.append(",item_display:\"").append(item.itemDisplay).append('"');
        }
        // since 1.19.4 on every display entity: sky and block light fixed at full
        if (item.fullBright) sb.append(",brightness:{sky:15,block:15}");
        if (item.glow) sb.append(",Glowing:1b,glow_color_override:").append(glowRgb(item.glowColor));
        float[] t = translation(item);
        float[] q = quaternion(item.rx, item.ry, item.rz);
        sb.append(",transformation:{left_rotation:[").append(f(q[0])).append(',').append(f(q[1])).append(',')
          .append(f(q[2])).append(',').append(f(q[3])).append("],right_rotation:[0f,0f,0f,1f],translation:[")
          .append(f(t[0])).append(',').append(f(t[1])).append(',').append(f(t[2])).append("],scale:[")
          .append(f(item.sx)).append(',').append(f(item.sy)).append(',').append(f(item.sz)).append("]}");
        sb.append(",Tags:[\"blstudio\",\"").append(tag).append("\"]}");
        return sb.toString();
    }

    /**
     * Where the entity's own origin has to be for the object's centre to land on its position.
     * A block_display draws the unit cube from its origin (a corner), so with rotation and scale
     * the corner is the centre minus R*(S*0.5). An item_display already draws centred.
     */
    public static float[] translation(Item item) {
        if (item.kind == Kind.ITEM) return new float[]{(float) item.x, (float) item.y, (float) item.z};
        double[][] r = matrix(item.rx, item.ry, item.rz);
        double hx = item.sx * 0.5, hy = item.sy * 0.5, hz = item.sz * 0.5;
        double cx = r[0][0] * hx + r[0][1] * hy + r[0][2] * hz;
        double cy = r[1][0] * hx + r[1][1] * hy + r[1][2] * hz;
        double cz = r[2][0] * hx + r[2][1] * hy + r[2][2] * hz;
        return new float[]{(float) (item.x - cx), (float) (item.y - cy), (float) (item.z - cz)};
    }

    /** Rotation matrix for X, then Y, then Z (R = Rz * Ry * Rx), in degrees. */
    public static double[][] matrix(double rxDeg, double ryDeg, double rzDeg) {
        double ax = Math.toRadians(rxDeg), ay = Math.toRadians(ryDeg), az = Math.toRadians(rzDeg);
        double cx = Math.cos(ax), sx = Math.sin(ax);
        double cy = Math.cos(ay), sy = Math.sin(ay);
        double cz = Math.cos(az), sz = Math.sin(az);
        double[][] rx = {{1, 0, 0}, {0, cx, -sx}, {0, sx, cx}};
        double[][] ry = {{cy, 0, sy}, {0, 1, 0}, {-sy, 0, cy}};
        double[][] rz = {{cz, -sz, 0}, {sz, cz, 0}, {0, 0, 1}};
        return mul(rz, mul(ry, rx));
    }

    /**
     * Turns the item about a world axis, the way Blender's rings do. The angles are applied X
     * first, so adding to rx alone turns about the object's own X once it has been turned about
     * Y or Z - the red ring then seemed to turn it about some other axis. Here the turn goes in
     * front of the whole rotation and the result is read back as angles.
     */
    public static void rotateWorld(Item item, char axis, double deg) {
        if (axis == 'Z' || (axis == 'Y' && item.rz == 0) || (axis == 'X' && item.ry == 0 && item.rz == 0)) {
            // the outermost angle is already a world turn: keep the numbers the player typed
            if (axis == 'X') item.rx = wrap(item.rx + deg);
            else if (axis == 'Y') item.ry = wrap(item.ry + deg);
            else item.rz = wrap(item.rz + deg);
            return;
        }
        double[][] turn = matrix(axis == 'X' ? deg : 0, axis == 'Y' ? deg : 0, axis == 'Z' ? deg : 0);
        double[] e = euler(mul(turn, matrix(item.rx, item.ry, item.rz)));
        item.rx = wrap(e[0]);
        item.ry = wrap(e[1]);
        item.rz = wrap(e[2]);
    }

    /** Angles X, Y, Z (degrees) of a rotation matrix R = Rz * Ry * Rx. */
    static double[] euler(double[][] r) {
        double sy = Math.max(-1, Math.min(1, -r[2][0]));
        double ry = Math.asin(sy);
        double rx, rz;
        if (Math.abs(sy) < 0.999999) {
            rx = Math.atan2(r[2][1], r[2][2]);
            rz = Math.atan2(r[1][0], r[0][0]);
        } else {
            // straight up or down: X and Z turn about the same line, so all of it goes to X
            rx = Math.atan2(-r[1][2], r[1][1]);
            rz = 0;
        }
        return new double[]{Math.toDegrees(rx), Math.toDegrees(ry), Math.toDegrees(rz)};
    }

    /** -180 < deg <= 180, to a ten-thousandth of a degree. */
    static double wrap(double deg) {
        double d = deg % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        d = Math.round(d * 10000) / 10000.0;
        return d == -180 ? 180 : d;
    }

    /** The same rotation as a unit quaternion [x, y, z, w], which is what the entity stores. */
    public static float[] quaternion(double rxDeg, double ryDeg, double rzDeg) {
        double[] qx = axisAngle(1, 0, 0, rxDeg);
        double[] qy = axisAngle(0, 1, 0, ryDeg);
        double[] qz = axisAngle(0, 0, 1, rzDeg);
        double[] q = qmul(qz, qmul(qy, qx));
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        return new float[]{(float) (q[0] / n), (float) (q[1] / n), (float) (q[2] / n), (float) (q[3] / n)};
    }

    private static double[] axisAngle(double ax, double ay, double az, double deg) {
        double h = Math.toRadians(deg) / 2;
        double s = Math.sin(h);
        return new double[]{ax * s, ay * s, az * s, Math.cos(h)};
    }

    private static double[] qmul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        return r;
    }

    /** "#RRGGBB" as the plain RGB number glow_color_override takes; white if unreadable. */
    static int glowRgb(String web) {
        try {
            String hex = web == null ? "" : web.trim().replace("#", "");
            return Integer.parseInt(hex.length() >= 6 ? hex.substring(0, 6) : "FFFFFF", 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    /** A float the way SNBT wants it: short, no exponent, with the f suffix. */
    static String f(double v) {
        if (Math.abs(v) < 1e-6) return "0f";
        String s = String.format(Locale.ROOT, "%.5f", v);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.equals("-0")) s = "0";
        return s + "f";
    }
}
