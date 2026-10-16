package ru.blauncher.blcore;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BLauncher capes as game textures: downloaded once per content hash, registered on the render
 * thread, then handed to the player renderer like any other cape.
 */
public final class Capes {

    private static final Map<String, Identifier> READY = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();

    private Capes() {}

    /** The texture for a cape hash, or null while it is still on its way (or failed). */
    public static Identifier texture(String hash) {
        if (hash == null) return null;
        Identifier id = READY.get(hash);
        if (id != null) return id;
        if (LOADING.putIfAbsent(hash, Boolean.TRUE) == null) {
            BlRegistry.submit(() -> load(hash));
        }
        return null;
    }

    private static void load(String hash) {
        try {
            byte[] bytes = hash.equals(BlConfig.selfCapeHash()) ? BlConfig.selfCapeBytes() : null;
            if (bytes == null) bytes = BlRegistry.downloadCape(hash);
            NativeImage image = NativeImage.read(bytes);
            if (image.getWidth() != 64 || image.getHeight() != 32) {
                image.close();
                return;   // only the standard layout; anything else would map onto the model wrong
            }
            Identifier id = Identifier.of("blcore", "capes/" + hash);
            MinecraftClient.getInstance().execute(() -> {
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
                READY.put(hash, id);
            });
        } catch (Exception e) {
            // keep LOADING set: a broken cape is not retried every frame
        }
    }
}
