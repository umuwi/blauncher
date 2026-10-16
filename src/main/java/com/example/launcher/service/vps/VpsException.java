package com.example.launcher.service.vps;

import java.io.IOException;

/**
 * Ошибка панели VPS с сообщением, которое можно показать человеку как есть. Технические
 * подробности (исходное исключение JSch, код выхода) — в cause и в лог, не в текст.
 */
public class VpsException extends IOException {

    public VpsException(String message) {
        super(message);
    }

    public VpsException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Ключ хоста не совпал с закреплённым. Это либо переустановка системы на VPS, либо подмена
     * сервера по дороге — отличить одно от другого лаунчер не может, поэтому решает человек.
     */
    public static final class HostKeyChanged extends VpsException {
        public final String expected;
        public final VpsSession.HostKeyInfo actual;

        public HostKeyChanged(String expected, VpsSession.HostKeyInfo actual) {
            super("Отпечаток ключа сервера изменился: было " + expected + ", сейчас " + actual.fingerprint()
                    + ". Так бывает после переустановки системы на VPS — или если кто-то подменяет сервер.");
            this.expected = expected;
            this.actual = actual;
        }
    }
}
