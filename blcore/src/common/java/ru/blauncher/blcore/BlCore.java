package ru.blauncher.blcore;

import net.fabricmc.api.ClientModInitializer;

/**
 * Entry point. Everything visible happens in the mixins (src/mc&lt;version&gt;); this only starts the
 * registry's background thread.
 */
public final class BlCore implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlRegistry.start();
    }
}
