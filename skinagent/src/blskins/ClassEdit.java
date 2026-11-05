package blskins;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Минимальная правка class-файла без ASM: метод переименовывается, а под его именем появляется обёртка,
 * которая вызывает его и отдаёт результат в {@link Hook}. В обёртке нет переходов, поэтому ей не нужна
 * StackMapTable, а остальной код класса остаётся байт в байт. Своя библиотека ASM тут была бы лишней:
 * её пришлось бы прятать от ASM, что уже есть в Fabric и Forge.
 */
final class ClassEdit {

    private final byte[] src;
    private final ByteArrayOutputStream extraPool = new ByteArrayOutputStream();
    private final DataOutputStream pool = new DataOutputStream(extraPool);
    private int poolCount;
    private final int poolEnd;
    private final int methodsAt;
    private final int thisClass;
    private final String[] utf;
    private final List<Method> methods = new ArrayList<>();
    private final int afterMethods;
    private final List<byte[]> added = new ArrayList<>();

    private static final class Method {
        int at;
        int access;
        int name;
        int desc;
        int end;
    }

    ClassEdit(byte[] bytes) {
        src = bytes;
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.getInt() != 0xCAFEBABE) throw new IllegalArgumentException("not a class");
        b.getInt();
        poolCount = b.getShort() & 0xFFFF;
        utf = new String[poolCount];
        for (int i = 1; i < poolCount; i++) {
            int tag = b.get() & 0xFF;
            switch (tag) {
                case 1: {
                    int len = b.getShort() & 0xFFFF;
                    utf[i] = new String(bytes, b.position(), len, StandardCharsets.UTF_8);
                    b.position(b.position() + len);
                    break;
                }
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    b.position(b.position() + 4);
                    break;
                case 5: case 6:
                    b.position(b.position() + 8);
                    i++;
                    break;
                case 7: case 8: case 16: case 19: case 20:
                    b.position(b.position() + 2);
                    break;
                case 15:
                    b.position(b.position() + 3);
                    break;
                default:
                    throw new IllegalArgumentException("constant tag " + tag);
            }
        }
        poolEnd = b.position();
        b.getShort();
        thisClass = b.getShort() & 0xFFFF;
        b.getShort();
        int interfaces = b.getShort() & 0xFFFF;
        b.position(b.position() + interfaces * 2);
        int fields = b.getShort() & 0xFFFF;
        for (int i = 0; i < fields; i++) skipMember(b);
        methodsAt = b.position();
        int count = b.getShort() & 0xFFFF;
        for (int i = 0; i < count; i++) {
            Method m = new Method();
            m.at = b.position();
            m.access = b.getShort() & 0xFFFF;
            m.name = b.getShort() & 0xFFFF;
            m.desc = b.getShort() & 0xFFFF;
            b.position(m.at);
            skipMember(b);
            m.end = b.position();
            methods.add(m);
        }
        afterMethods = b.position();
    }

    private static void skipMember(ByteBuffer b) {
        b.position(b.position() + 6);
        int attributes = b.getShort() & 0xFFFF;
        for (int i = 0; i < attributes; i++) {
            b.getShort();
            int len = b.getInt();
            b.position(b.position() + len);
        }
    }

    /** Оборачивает метод; false — такого метода в этой версии библиотеки нет. */
    boolean wrap(String name, String desc, String hook) throws IOException {
        Method target = null;
        for (Method m : methods) {
            // m.name за концом таблицы — метод уже переименован прошлым wrap.
            if (m.name < utf.length && name.equals(utf[m.name]) && desc.equals(utf[m.desc])) target = m;
        }
        if (target == null) return false;
        if ((target.access & 0x0408) != 0) return false;   // абстрактный или статический — не наш случай

        int origName = utf8("bl$orig$" + name);
        int newOrig = methodRef(thisClass, origName, utf8(desc), 10);
        // Hook берём через системный загрузчик, а не прямой ссылкой. 05.11.2026: у Fabric загрузчик Knot видит
        // класс в classpath, но не отдаёт его игре («hasn't been exposed»): NoClassDefFoundError и вылет.
        int stringHook = string("blskins.Hook");
        int stringField = string(hook.toUpperCase(java.util.Locale.ROOT));
        int getLoader = methodRef(classRef("java/lang/ClassLoader"), utf8("getSystemClassLoader"), utf8("()Ljava/lang/ClassLoader;"), 10);
        int forName = methodRef(classRef("java/lang/Class"), utf8("forName"),
                utf8("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"), 10);
        int getField = methodRef(classRef("java/lang/Class"), utf8("getField"),
                utf8("(Ljava/lang/String;)Ljava/lang/reflect/Field;"), 10);
        int fieldGet = methodRef(classRef("java/lang/reflect/Field"), utf8("get"),
                utf8("(Ljava/lang/Object;)Ljava/lang/Object;"), 10);
        int biFunction = classRef("java/util/function/BiFunction");
        int apply = methodRef(biFunction, utf8("apply"),
                utf8("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), 11);
        String ret = desc.substring(desc.indexOf(')') + 2, desc.length() - 1);
        int retClass = classRef(ret);
        boolean flag = desc.contains(";Z)");

        ByteArrayOutputStream code = new ByteArrayOutputStream();
        ldc(code, stringHook);
        code.write(0x04);                                  // iconst_1
        invoke(code, 0xB8, getLoader);
        invoke(code, 0xB8, forName);
        ldc(code, stringField);
        invoke(code, 0xB6, getField);
        code.write(0x01);                                  // aconst_null
        invoke(code, 0xB6, fieldGet);
        code.write(0xC0);                                  // checkcast BiFunction
        code.write(biFunction >> 8);
        code.write(biFunction);
        code.write(0x2A);                                  // aload_0
        code.write(0x2B);                                  // aload_1
        if (flag) code.write(0x1C);                        // iload_2
        code.write(0xB6);                                  // invokevirtual bl$orig$…
        code.write(newOrig >> 8);
        code.write(newOrig);
        code.write(0x2B);                                  // aload_1
        code.write(0xB9);                                  // invokeinterface BiFunction.apply
        code.write(apply >> 8);
        code.write(apply);
        code.write(3);
        code.write(0);
        code.write(0xC0);                                  // checkcast
        code.write(retClass >> 8);
        code.write(retClass);
        code.write(0xB0);                                  // areturn

        ByteArrayOutputStream method = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(method);
        out.writeShort(target.access & ~0x0010);           // без final
        out.writeShort(target.name);
        out.writeShort(target.desc);
        out.writeShort(1);
        out.writeShort(utf8("Code"));
        out.writeInt(2 + 2 + 4 + code.size() + 2 + 2);
        out.writeShort(flag ? 5 : 4);                      // max_stack
        out.writeShort(flag ? 3 : 2);                      // max_locals
        out.writeInt(code.size());
        code.writeTo(out);
        out.writeShort(0);                                 // исключений нет
        out.writeShort(0);                                 // атрибутов нет
        added.add(method.toByteArray());
        target.name = origName;
        return true;
    }

    private static void ldc(ByteArrayOutputStream code, int index) {
        code.write(0x13);                                  // ldc_w
        code.write(index >> 8);
        code.write(index);
    }

    private static void invoke(ByteArrayOutputStream code, int op, int index) {
        code.write(op);
        code.write(index >> 8);
        code.write(index);
    }

    private int string(String s) throws IOException {
        int text = utf8(s);
        pool.writeByte(8);
        pool.writeShort(text);
        return poolCount++;
    }

    private int utf8(String s) throws IOException {
        pool.writeByte(1);
        pool.writeUTF(s);
        return poolCount++;
    }

    private int classRef(String internal) throws IOException {
        int name = utf8(internal);
        pool.writeByte(7);
        pool.writeShort(name);
        return poolCount++;
    }

    private int methodRef(int owner, int name, int desc, int tag) throws IOException {
        pool.writeByte(12);
        pool.writeShort(name);
        pool.writeShort(desc);
        int nameAndType = poolCount++;
        pool.writeByte(tag);
        pool.writeShort(owner);
        pool.writeShort(nameAndType);
        return poolCount++;
    }

    byte[] toBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(src.length + 512);
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(src, 0, 8);
        out.writeShort(poolCount);
        out.write(src, 10, poolEnd - 10);
        extraPool.writeTo(out);
        out.write(src, poolEnd, methodsAt - poolEnd);
        out.writeShort(methods.size() + added.size());
        for (Method m : methods) {
            out.writeShort(m.access);
            out.writeShort(m.name);
            out.writeShort(m.desc);
            out.write(src, m.at + 6, m.end - m.at - 6);
        }
        for (byte[] m : added) out.write(m);
        out.write(src, afterMethods, src.length - afterMethods);
        return bytes.toByteArray();
    }
}
