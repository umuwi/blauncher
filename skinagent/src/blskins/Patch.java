package blskins;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Находит сервис сессий authlib и оборачивает его методы. Какой сервис и какие методы — зависит от версии игры. */
public final class Patch implements ClassFileTransformer {

    private static final String[] SERVICES = {
            "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService",   // до 1.21.x
            "com/mojang/authlib/services/MinecraftServicesSessionService",     // 1.21.9 и новее
    };
    private static final String PROFILE = "Lcom/mojang/authlib/GameProfile;";
    private static final String PROPERTY = "Lcom/mojang/authlib/properties/Property;";
    private static final String TEXTURES = "Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;";

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (name == null || redefined != null) return null;
        boolean ours = false;
        for (String s : SERVICES) ours |= s.equals(name);
        if (!ours) return null;
        try {
            ClassEdit edit = new ClassEdit(bytes);
            boolean any = edit.wrap("getPackedTextures", "(" + PROFILE + ")" + PROPERTY, "packed");
            any |= edit.wrap("unpackTextures", "(" + PROPERTY + ")" + TEXTURES, "unpack");
            any |= edit.wrap("getTextures", "(" + PROFILE + "Z)Ljava/util/Map;", "legacy");
            if (!any) return null;
            System.out.println("[BLSKINS] attached: " + name);
            return edit.toBytes();
        } catch (Throwable t) {
            System.out.println("[BLSKINS] class left as is: " + t);
            return null;
        }
    }
}
