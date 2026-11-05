package blskins;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Что делают обёртки в authlib. Работает через отражение: у агента нет своей копии authlib, а её классы
 * от версии к версии менялись (1.21.9 — GameProfile стал записью, 1.20.2 — текстуры упакованы в Property).
 * Настройки приходят из -D лаунчера. Текстура подставляется только своему нику и только если настоящей
 * нет: лицензионный скин и чужие игроки остаются как были. Любая ошибка — результат без изменений.
 */
public final class Hook {

    private static final String NAME = System.getProperty("blskins.name", "");
    private static final String SKIN = System.getProperty("blskins.skin", "");
    private static final String CAPE = System.getProperty("blskins.cape", "");
    private static final boolean SLIM = Boolean.getBoolean("blskins.slim");

    /** Значение нашей Property. Настоящих текстур внутри нет, чтобы проверка домена в authlib молчала. */
    private static final String MARK = Base64.getEncoder().encodeToString(
            "{\"textures\":{}}"
                    .getBytes(StandardCharsets.UTF_8));

    // Обёртки в authlib берут эти поля по имени (см. ClassEdit): прямая ссылка на класс падает на Fabric.
    public static final BiFunction<Object, Object, Object> PACKED = Hook::packed;
    public static final BiFunction<Object, Object, Object> UNPACK = Hook::unpack;
    public static final BiFunction<Object, Object, Object> LEGACY = Hook::legacy;

    private static volatile boolean reported;

    private Hook() {
    }

    /** Один раз за запуск: лаунчер видит эту строку в выводе игры и отправляет отметку blskins. */
    private static void report(String where, Throwable t) {
        if (reported) return;
        reported = true;
        System.out.println("[BLSKINS] error " + where + " " + t.getClass().getSimpleName());
    }

    /** getPackedTextures(GameProfile), 1.20.2 и новее: своему нику без текстур — наша метка. */
    public static Object packed(Object result, Object profile) {
        try {
            if (result != null || !isSelf(profile)) return result;
            Class<?> property = Class.forName("com.mojang.authlib.properties.Property", true, profile.getClass().getClassLoader());
            return property.getConstructor(String.class, String.class).newInstance("textures", MARK);
        } catch (Throwable t) {
            report("packed", t);
            return result;
        }
    }

    /** unpackTextures(Property), 1.20.2 и новее: наша метка превращается в настоящие адреса. */
    public static Object unpack(Object result, Object property) {
        try {
            if (property == null || !MARK.equals(value(property))) return result;
            ClassLoader loader = property.getClass().getClassLoader();
            Class<?> textures = Class.forName("com.mojang.authlib.minecraft.MinecraftProfileTextures", true, loader);
            Constructor<?> make = textures.getConstructors()[0];
            Class<?>[] types = make.getParameterTypes();
            Object[] args = new Object[types.length];
            int textureSlot = 0;
            for (int i = 0; i < types.length; i++) {
                if (types[i].isEnum()) {
                    // SIGNED: проверять подпись нечему, а без неё игра может отказать в текстуре.
                    for (Object c : types[i].getEnumConstants()) {
                        if ("SIGNED".equals(((Enum<?>) c).name())) args[i] = c;
                    }
                } else {
                    args[i] = textureSlot == 0 ? texture(loader, SKIN, SLIM) : textureSlot == 1 ? texture(loader, CAPE, false) : null;
                    textureSlot++;
                }
            }
            return make.newInstance(args);
        } catch (Throwable t) {
            report("unpack", t);
            return result;
        }
    }

    /** getTextures(GameProfile, boolean), 1.7–1.20.1: сразу готовый набор текстур. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object legacy(Object result, Object profile) {
        try {
            if (!isSelf(profile)) return result;
            Map<?, ?> found = (Map<?, ?>) result;
            if (found != null && !found.isEmpty()) return result;
            ClassLoader loader = profile.getClass().getClassLoader();
            Class type = Class.forName("com.mojang.authlib.minecraft.MinecraftProfileTexture$Type", true, loader);
            Map out = new HashMap();
            if (!SKIN.isEmpty()) out.put(Enum.valueOf(type, "SKIN"), texture(loader, SKIN, SLIM));
            if (!CAPE.isEmpty()) out.put(Enum.valueOf(type, "CAPE"), texture(loader, CAPE, false));
            return out.isEmpty() ? result : out;
        } catch (Throwable t) {
            report("legacy", t);
            return result;
        }
    }

    private static boolean isSelf(Object profile) throws Exception {
        if (profile == null || NAME.isEmpty()) return false;
        return NAME.equals(call(profile, "getName", "name"));
    }

    private static String value(Object property) throws Exception {
        return (String) call(property, "getValue", "value");
    }

    private static Object call(Object target, String oldName, String newName) throws Exception {
        Method m;
        try {
            m = target.getClass().getMethod(newName);
        } catch (NoSuchMethodException e) {
            m = target.getClass().getMethod(oldName);
        }
        return m.invoke(target);
    }

    private static Object texture(ClassLoader loader, String url, boolean slim) throws Exception {
        if (url.isEmpty()) return null;
        Class<?> texture = Class.forName("com.mojang.authlib.minecraft.MinecraftProfileTexture", true, loader);
        Map<String, String> meta = slim ? Collections.singletonMap("model", "slim") : null;
        return texture.getConstructor(String.class, Map.class).newInstance(url, meta);
    }
}
