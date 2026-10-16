package com.example.launcher.service;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.*;
import java.nio.file.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Patches the Minecraft client JAR so that the "Multiplayer" button is always enabled
 * (i.e., isMultiplayerEnabled() returns true regardless of internet connection).
 */
public class VersionPatcher {

    public static void patchClientJar(Path clientJar) throws IOException {
        Path patchedJar = clientJar.resolveSibling(clientJar.getFileName() + ".patched");
        try {
            patchInto(clientJar, patchedJar);
        } catch (IOException | RuntimeException e) {
            // Vanilla client jars are obfuscated, so "net/minecraft/client/Minecraft.class" isn't
            // in them and this throws for every normal version - leaving behind a full-size copy
            // of the client jar (~17 MB per version) that nothing ever reads or deletes.
            try { Files.deleteIfExists(patchedJar); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static void patchInto(Path clientJar, Path patchedJar) throws IOException {
        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(Files.newInputStream(clientJar)));
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(patchedJar)))) {
            boolean patched = false;
            byte[] buffer = new byte[8192];
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                byte[] classBytes;
                if (name.equals("net/minecraft/client/Minecraft.class")) {
                    classBytes = applyPatch(jis);
                    patched = true;
                } else {
                    classBytes = readAllBytes(jis);
                }
                JarEntry newEntry = new JarEntry(name);
                newEntry.setTime(entry.getTime());
                jos.putNextEntry(newEntry);
                jos.write(classBytes);
                jos.closeEntry();
            }
            if (!patched) {
                throw new IOException("Minecraft class not found in JAR");
            }
        }
        // Replace original with patched
        Files.move(patchedJar, clientJar, StandardCopyOption.REPLACE_EXISTING);
        // Новый размер — рядом: VersionDownloadService.isVersionFullyInstalled сверяет jar с размером
        // Mojang и без этой отметки считал бы пропатченный jar битым и перекачивал при каждом запуске.
        Files.writeString(clientJar.resolveSibling(clientJar.getFileName() + ".blpatched"),
                String.valueOf(Files.size(clientJar)));
    }

    private static byte[] applyPatch(InputStream classStream) throws IOException {
        ClassReader cr = new ClassReader(classStream);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals("isMultiplayerEnabled") || name.equals("m_") /* some Yarn mappings */) {
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                        @Override
                        public void visitCode() {
                            mv.visitInsn(Opcodes.ICONST_1); // push true
                            mv.visitInsn(Opcodes.IRETURN);
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }
}
