package com.example.launcher.ui.worldcalc;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The arithmetic behind the portal and stronghold tools, without any UI.
 *
 * <p>Numbers are the Java Edition ones from minecraft.wiki: portals scale X and Z by 8 and keep Y,
 * the search square has a radius of 128 blocks in the Overworld and 16 in the Nether (since
 * 1.16.2), and strongholds generate in eight rings around the world origin.
 */
public final class WorldCalc {

    public static final int OVERWORLD_SEARCH = 128;
    public static final int NETHER_SEARCH = 16;

    /** How far a read of the eye's direction can be off, in degrees. Drives the ± of a result. */
    static final double AIM_ERROR = 0.3;
    /** Below this angle between two throws the lines barely cross and the answer means nothing. */
    private static final double MIN_ANGLE = 0.5;

    private static final int[][] RINGS = {
            {1280, 2816}, {4352, 5888}, {7424, 8960}, {10496, 12032},
            {13568, 15104}, {16640, 18176}, {19712, 21248}, {22784, 24320}};

    private static final Pattern F3C = Pattern.compile(
            "(?:in\\s+minecraft:(\\w+)\\s+run\\s+)?tp\\s+@s\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)");

    private WorldCalc() {
    }

    /** A block position. {@code y} is null when the player left it out: it then adds no distance. */
    public record Block(long x, Integer y, long z) {
    }

    /** Where a portal looks for its partner, how far off the other one stands, and whether it is found. */
    public record Link(Block target, long dx, long dz, boolean found) {
    }

    /** One throw: where the player stood and the first number of F3's "Facing" (the yaw). */
    public record Throw(double x, double z, double yaw) {
    }

    public enum Problem { NONE, TOO_PARALLEL, DIVERGING }

    /** {@code spread} is how many blocks the point moves if the aim was off by {@link #AIM_ERROR}. */
    public record Fix(Problem problem, double x, double z, double spread) {
    }

    /** {@code ring} is 1..8, or 0 when even with the spread the point lies outside every ring. */
    public record RingInfo(int ring, double fromOrigin, double outsideBy) {
    }

    /** The text of F3+C: {@code /execute in minecraft:overworld run tp @s x y z yaw pitch}. */
    public record Location(String dimension, double x, double y, double z, double yaw) {
        public boolean nether() {
            return "the_nether".equals(dimension);
        }
    }

    public static Block toNether(Block overworld) {
        return new Block(Math.floorDiv(overworld.x(), 8L), overworld.y(), Math.floorDiv(overworld.z(), 8L));
    }

    public static Block toOverworld(Block nether) {
        return new Block(nether.x() * 8, nether.y(), nether.z() * 8);
    }

    public static double distance(Block a, Block b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        double dy = a.y() != null && b.y() != null ? a.y() - b.y() : 0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Going down from the Overworld portal: does the game find the Nether one? */
    public static Link linkToNether(Block overworld, Block nether) {
        return link(toNether(overworld), nether, NETHER_SEARCH);
    }

    /** Going up from the Nether portal: does the game find the Overworld one? */
    public static Link linkToOverworld(Block nether, Block overworld) {
        return link(toOverworld(nether), overworld, OVERWORLD_SEARCH);
    }

    private static Link link(Block target, Block portal, int radius) {
        long dx = portal.x() - target.x();
        long dz = portal.z() - target.z();
        return new Link(target, dx, dz, Math.abs(dx) <= radius && Math.abs(dz) <= radius);
    }

    /** Crosses the two lines of flight. Yaw 0 looks along +Z, yaw 90 along -X. */
    public static Fix triangulate(Throw a, Throw b) {
        double[] point = cross(a, b);
        if (Double.isNaN(point[0])) {
            return new Fix(Problem.TOO_PARALLEL, 0, 0, 0);
        }
        if (point[2] <= 0 || point[3] <= 0) {
            return new Fix(Problem.DIVERGING, 0, 0, 0);
        }
        double spread = 0;
        for (double ea : new double[]{-AIM_ERROR, AIM_ERROR}) {
            for (double eb : new double[]{-AIM_ERROR, AIM_ERROR}) {
                double[] p = cross(new Throw(a.x(), a.z(), a.yaw() + ea), new Throw(b.x(), b.z(), b.yaw() + eb));
                // A shift that makes the lines parallel means the answer can be anywhere.
                spread = Double.isNaN(p[0]) ? Double.POSITIVE_INFINITY
                        : Math.max(spread, Math.hypot(p[0] - point[0], p[1] - point[1]));
            }
        }
        return new Fix(Problem.NONE, point[0], point[1], spread);
    }

    /** x, z, and how far along each line the point is; NaN when the lines are close to parallel. */
    private static double[] cross(Throw a, Throw b) {
        double ax = -Math.sin(Math.toRadians(a.yaw()));
        double az = Math.cos(Math.toRadians(a.yaw()));
        double bx = -Math.sin(Math.toRadians(b.yaw()));
        double bz = Math.cos(Math.toRadians(b.yaw()));
        double det = ax * bz - az * bx;
        if (Math.abs(det) < Math.sin(Math.toRadians(MIN_ANGLE))) {
            return new double[]{Double.NaN, Double.NaN, 0, 0};
        }
        double ox = b.x() - a.x();
        double oz = b.z() - a.z();
        double t = (ox * bz - oz * bx) / det;
        double u = (ox * az - oz * ax) / det;
        return new double[]{a.x() + t * ax, a.z() + t * az, t, u};
    }

    /** Strongholds only generate in rings, so a point far from all of them is a misread angle. */
    public static RingInfo ring(double x, double z, double spread) {
        double from = Math.hypot(x, z);
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < RINGS.length; i++) {
            double outside = Math.max(0, Math.max(RINGS[i][0] - from, from - RINGS[i][1]));
            if (outside <= spread) {
                return new RingInfo(i + 1, from, 0);
            }
            nearest = Math.min(nearest, outside);
        }
        return new RingInfo(0, from, nearest);
    }

    /** Accepts a comma as the decimal point and a typographic minus; null when it is not a number. */
    public static Double number(String text) {
        if (text == null) return null;
        String s = text.trim().replace(',', '.').replace('−', '-');
        if (s.isEmpty()) return null;
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Location location(String text) {
        if (text == null) return null;
        Matcher m = F3C.matcher(text.toLowerCase(Locale.ROOT));
        if (!m.find()) return null;
        Double x = number(m.group(2));
        Double y = number(m.group(3));
        Double z = number(m.group(4));
        Double yaw = number(m.group(5));
        if (x == null || y == null || z == null || yaw == null) return null;
        return new Location(m.group(1), x, y, z, yaw);
    }
}
