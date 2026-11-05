package blskins;

import java.lang.instrument.Instrumentation;

/**
 * BL Skins: свой скин и плащ в игре без мода и без лицензии (05.11.2026, просьба владельца).
 *
 * <p>Это java-агент, а не мод: он подключается ключом -javaagent и поэтому работает на чистой игре и
 * с любым загрузчиком. Игра берёт текстуры игрока из библиотеки authlib, а у пиратского аккаунта их там
 * нет; агент дописывает в её сервис сессий две обёртки (см. {@link Hook}). Классы самой игры не трогает:
 * они обфусцированы и меняются от версии к версии, а authlib — нет.
 *
 * <p>Любой сбой здесь не должен мешать игре: тогда класс остаётся как был, и в игре просто обычный скин.
 */
public final class Agent {

    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        try {
            inst.addTransformer(new Patch());
        } catch (Throwable t) {
            System.out.println("[BLSKINS] not attached: " + t);
        }
    }
}
