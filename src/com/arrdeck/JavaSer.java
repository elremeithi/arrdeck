package com.arrdeck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, class-loading-free reader for Java serialization streams (ObjectOutputStream).
 * nzb360 backups are zips of serialized SharedPreferences maps and of its own indexer objects;
 * this turns any such object graph into org.json values without instantiating anything:
 *   - HashMap / LinkedHashMap / Hashtable / TreeMap -> JSONObject (string keys)
 *   - ArrayList / HashSet / arrays / other collections -> JSONArray
 *   - Boolean / Integer / Long / ... -> their value
 *   - any other object -> JSONObject of its serialized fields (+ "@class")
 */
final class JavaSer {
    private static final int BASE_HANDLE = 0x7E0000;
    private static final byte TC_NULL = 0x70, TC_REFERENCE = 0x71, TC_CLASSDESC = 0x72, TC_OBJECT = 0x73,
            TC_STRING = 0x74, TC_ARRAY = 0x75, TC_CLASS = 0x76, TC_BLOCKDATA = 0x77, TC_ENDBLOCKDATA = 0x78,
            TC_RESET = 0x79, TC_BLOCKDATALONG = 0x7A, TC_EXCEPTION = 0x7B, TC_LONGSTRING = 0x7C,
            TC_PROXYCLASSDESC = 0x7D, TC_ENUM = 0x7E;
    private static final int SC_WRITE_METHOD = 0x01, SC_SERIALIZABLE = 0x02, SC_EXTERNALIZABLE = 0x04, SC_BLOCK_DATA = 0x08;

    static final class Field {
        char type;
        String name;
    }

    static final class ClassDesc {
        String name;
        int flags;
        Field[] fields = new Field[0];
        ClassDesc sup;
    }

    /** Marker for raw block data inside custom writeObject sections. */
    static final class Block {
        final byte[] data;

        Block(byte[] d) { data = d; }
    }

    private final DataInputStream in;
    private final List<Object> handles = new ArrayList<>();
    private int depth;

    private JavaSer(byte[] data) { in = new DataInputStream(new ByteArrayInputStream(data)); }

    static boolean looksLike(byte[] d) {
        return d.length > 4 && (d[0] & 0xff) == 0xAC && (d[1] & 0xff) == 0xED;
    }

    /** Returns every top-level object in the stream, converted to JSON-friendly values. */
    static List<Object> read(byte[] data) throws IOException {
        JavaSer s = new JavaSer(data);
        if (s.in.readUnsignedShort() != 0xACED) throw new IOException("not a Java serialization stream");
        s.in.readUnsignedShort(); // version
        List<Object> out = new ArrayList<>();
        while (s.in.available() > 0) {
            Object o = s.content();
            if (!(o instanceof Block)) out.add(o);
        }
        return out;
    }

    private int newHandle(Object o) {
        handles.add(o);
        return handles.size() - 1;
    }

    private Object content() throws IOException {
        byte tc = in.readByte();
        return content(tc);
    }

    private Object content(byte tc) throws IOException {
        if (++depth > 200) throw new IOException("too deep");
        try {
            switch (tc) {
                case TC_NULL: return JSONObject.NULL;
                case TC_REFERENCE: {
                    int h = in.readInt() - BASE_HANDLE;
                    if (h < 0 || h >= handles.size()) throw new IOException("bad handle");
                    Object o = handles.get(h);
                    return o == null ? JSONObject.NULL : o;
                }
                case TC_STRING: { String s = in.readUTF(); newHandle(s); return s; }
                case TC_LONGSTRING: {
                    long len = in.readLong();
                    if (len > 10_000_000) throw new IOException("string too long");
                    byte[] b = new byte[(int) len];
                    in.readFully(b);
                    String s = new String(b, "UTF-8");
                    newHandle(s);
                    return s;
                }
                case TC_CLASSDESC: case TC_PROXYCLASSDESC: return classDesc(tc);
                case TC_CLASS: { ClassDesc cd = classDescAny(); newHandle(cd); return cd.name; }
                case TC_ENUM: {
                    classDescAny();
                    int h = newHandle(null);
                    Object name = content();
                    handles.set(h, name);
                    return name;
                }
                case TC_ARRAY: return array();
                case TC_OBJECT: return object();
                case TC_BLOCKDATA: { byte[] b = new byte[in.readUnsignedByte()]; in.readFully(b); return new Block(b); }
                case TC_BLOCKDATALONG: {
                    int n = in.readInt();
                    if (n < 0 || n > 10_000_000) throw new IOException("bad block");
                    byte[] b = new byte[n];
                    in.readFully(b);
                    return new Block(b);
                }
                case TC_RESET: handles.clear(); return new Block(new byte[0]);
                case TC_EXCEPTION: throw new IOException("stream contains exception");
                default: throw new IOException("unknown type code " + tc);
            }
        } finally { depth--; }
    }

    private ClassDesc classDescAny() throws IOException {
        byte tc = in.readByte();
        if (tc == TC_NULL) return null;
        if (tc == TC_REFERENCE) {
            Object o = handles.get(in.readInt() - BASE_HANDLE);
            if (!(o instanceof ClassDesc)) throw new IOException("expected class desc");
            return (ClassDesc) o;
        }
        if (tc == TC_CLASSDESC || tc == TC_PROXYCLASSDESC) return classDesc(tc);
        throw new IOException("expected class desc, got " + tc);
    }

    private ClassDesc classDesc(byte tc) throws IOException {
        ClassDesc cd = new ClassDesc();
        if (tc == TC_PROXYCLASSDESC) {
            newHandle(cd);
            int n = in.readInt();
            for (int i = 0; i < n; i++) in.readUTF();
            cd.name = "$Proxy";
        } else {
            cd.name = in.readUTF();
            in.readLong(); // serialVersionUID
            newHandle(cd);
            cd.flags = in.readUnsignedByte();
            int n = in.readUnsignedShort();
            cd.fields = new Field[n];
            for (int i = 0; i < n; i++) {
                Field f = new Field();
                f.type = (char) in.readUnsignedByte();
                f.name = in.readUTF();
                if (f.type == 'L' || f.type == '[') content(); // field class name (string or reference)
                cd.fields[i] = f;
            }
        }
        annotation(null);
        cd.sup = classDescAny();
        return cd;
    }

    /** Reads contents up to TC_ENDBLOCKDATA, collecting objects (blocks too, if wanted). */
    private void annotation(List<Object> sink) throws IOException {
        while (true) {
            byte tc = in.readByte();
            if (tc == TC_ENDBLOCKDATA) return;
            Object o = content(tc);
            if (sink != null) sink.add(o);
        }
    }

    private Object array() throws IOException {
        ClassDesc cd = classDescAny();
        JSONArray a = new JSONArray();
        newHandle(a);
        int n = in.readInt();
        if (n < 0 || n > 5_000_000) throw new IOException("bad array length");
        char t = cd != null && cd.name.length() > 1 ? cd.name.charAt(1) : 'L';
        for (int i = 0; i < n; i++) a.put(value(t));
        return a;
    }

    private Object value(char t) throws IOException {
        switch (t) {
            case 'B': return (int) in.readByte();
            case 'C': return String.valueOf(in.readChar());
            case 'D': return in.readDouble();
            case 'F': return (double) in.readFloat();
            case 'I': return in.readInt();
            case 'J': return in.readLong();
            case 'S': return (int) in.readShort();
            case 'Z': return in.readBoolean();
            default: return content();
        }
    }

    private static boolean isMap(String n) {
        return n.equals("java.util.HashMap") || n.equals("java.util.LinkedHashMap") || n.equals("java.util.Hashtable")
                || n.equals("java.util.TreeMap") || n.equals("java.util.concurrent.ConcurrentHashMap") || n.equals("java.util.Properties");
    }

    private static boolean isCollection(String n) {
        return n.equals("java.util.ArrayList") || n.equals("java.util.HashSet") || n.equals("java.util.LinkedHashSet")
                || n.equals("java.util.LinkedList") || n.equals("java.util.TreeSet") || n.equals("java.util.Vector")
                || n.equals("java.util.ArrayDeque") || n.startsWith("java.util.Collections$");
    }

    private Object object() throws IOException {
        ClassDesc cd = classDescAny();
        if (cd == null) throw new IOException("object without class");
        String name = cd.name;
        // class hierarchy, superclass first
        List<ClassDesc> chain = new ArrayList<>();
        for (ClassDesc c = cd; c != null; c = c.sup) chain.add(0, c);

        final boolean map = isMap(name), coll = isCollection(name);
        JSONObject fields = new JSONObject();
        JSONArray list = new JSONArray();
        Object result = map ? new JSONObject() : coll ? list : fields;
        int h = newHandle(result);
        List<Object> extra = new ArrayList<>();
        for (ClassDesc c : chain) {
            if ((c.flags & SC_EXTERNALIZABLE) != 0) {
                if ((c.flags & SC_BLOCK_DATA) == 0) throw new IOException("old externalizable format");
                annotation(extra);
                continue;
            }
            if ((c.flags & SC_SERIALIZABLE) == 0) continue;
            for (Field f : c.fields) {
                try { fields.put(f.name, value(f.type)); } catch (org.json.JSONException e) { throw new IOException(e); }
            }
            if ((c.flags & SC_WRITE_METHOD) != 0) annotation(extra);
        }
        try {
            List<Object> objs = new ArrayList<>();
            for (Object o : extra) if (!(o instanceof Block)) objs.add(o);
            if (map) {
                JSONObject m = (JSONObject) result;
                for (int i = 0; i + 1 < objs.size(); i += 2) m.put(String.valueOf(objs.get(i)), objs.get(i + 1));
            } else if (coll) {
                for (Object o : objs) list.put(o);
            } else {
                // boxed primitives collapse to their value
                if ((name.startsWith("java.lang.") && fields.has("value") && fields.length() == 1)) {
                    Object v = fields.get("value");
                    handles.set(h, v);
                    return v;
                }
                fields.put("@class", name);
                if (!objs.isEmpty()) {
                    JSONArray ex = new JSONArray();
                    for (Object o : objs) ex.put(o);
                    fields.put("@data", ex);
                }
            }
        } catch (org.json.JSONException e) { throw new IOException(e); }
        return result;
    }
}
