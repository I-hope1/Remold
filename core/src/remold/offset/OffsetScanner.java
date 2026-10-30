package remold.offset;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * 偏移量绑定扫描器（S1）。
 *
 * 回答："类 T 的字段 f，有哪些地方把它的偏移量 / 访问器缓存了下来？"
 *
 * 设计要点：
 *  1. ASM Analyzer + 自定义 Interpreter 做数据流，值格子见 {@link AV}。
 *  2. Interpreter 本身无副作用（除按需触发被调方法摘要计算）。落点（sink）在
 *     Analyzer 收敛之后，用最终帧做一遍后处理来记录，避免循环中间态产生的伪绑定与重复。
 *  3. 方法摘要 = 该方法返回值的符号表达式，参数用 {@link Sym} 占位，调用点代入实参。
 *     这样 helper 方法、doPrivileged 内部类 / lambda 都走同一套机制。
 *  4. 不依赖 JVM 运行时：只吃字节码，由 {@link ClassSource} 提供。
 */
public final class OffsetScanner {

    public static final int MAX_DEPTH = 3;

    /* ======================= 对外模型 ======================= */

    public enum Kind { INSTANCE_OFFSET, STATIC_OFFSET, STATIC_BASE, UPDATER, VARHANDLE, @Deprecated METHODHANDLE }
    public enum Sink { STATIC_FIELD, INSTANCE_FIELD, ESCAPED }
    public enum Confidence { EXACT, WILDCARD_FIELD, UNRESOLVED_CLASS }

    public record Binding(
            String targetClass,   // 内部名；null = 未解析
            String targetField,   // null = 通配
            Kind kind, Sink sink,
            String holderClass, String holderMember,
            String site, Confidence conf) {}

    @FunctionalInterface
    public interface ClassSource {
        /** 返回类字节码，读不到返回 null（计入盲区报告）。 */
        byte[] bytes(String internalName);
    }

    public static final class Report {
        public final Set<Binding> bindings = new LinkedHashSet<>();
        public final Map<String, Set<String>> fieldReaders = new LinkedHashMap<>();
        public final Set<String> unreadable = new TreeSet<>();
        public final Set<String> analysisFailed = new TreeSet<>();
        public int scannedClasses, skippedByPrefilter, depthExceeded, cycles;
        @Override public String toString() {
            return "scanned=" + scannedClasses + " skipped=" + skippedByPrefilter + " bindings=" + bindings.size()
                    + " readers=" + fieldReaders.size()
                    + " unreadable=" + unreadable.size() + " failed=" + analysisFailed.size()
                    + " depthExceeded=" + depthExceeded + " cycles=" + cycles;
        }
    }

    /* ======================= 值格子 ======================= */

    /**
     * 符号：k = 'C' 常量(v) | 'U' 未知 | 'P' 第 p 个实参（按槽位角色取其 Class/String 符号）
     *       'S' 第 p 个 String 实参，当作类名（Class.forName(param)）
     *       'Q' 第 p 个 Field 实参的 declaringClass | 'R' 第 p 个 Field 实参的 name
     */
    record Sym(char k, String v, int p) {
        static final Sym UNK = new Sym('U', null, -1);
        static Sym c(String s) { return new Sym('C', s, -1); }
        boolean isConst() { return k == 'C'; }
        Sym merge(Sym o) { return equals(o) ? this : UNK; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Sym other)) return false;
            return k == other.k && p == other.p && Objects.equals(v, other.v);
        }

        @Override
        public int hashCode() {
            return Objects.hash(k, v, p);
        }
    }

    enum Tag {
        TOP,      // 未知
        BOT,      // null / 数值常量 / 未初始化：不携带绑定，merge 时让位
        CLASS,    // a = 类符号
        STR,      // a = 字符串符号
        FIELD,    // a = 类符号, b = 名符号
        FIELDS,   // a = 类符号   (Field[])
        CALLABLE, // fn = 可被 doPrivileged 回调的方法（匿名类 run() / lambda 实现方法）
        PROD      // 偏移量 / Updater / 句柄: kind, a = 目标类, b = 目标字段名
    }

    record Fn(String owner, String name, String desc) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Fn other)) return false;
            return Objects.equals(owner, other.owner)
                    && Objects.equals(name, other.name)
                    && Objects.equals(desc, other.desc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, name, desc);
        }
    }

    record AV(int size, Tag tag, Sym a, Sym b, Kind kind, Fn fn) implements Value {
        @Override public int getSize() { return size; }
        boolean is(Tag t) { return tag == t; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AV other)) return false;
            return size == other.size
                    && tag == other.tag
                    && Objects.equals(a, other.a)
                    && Objects.equals(b, other.b)
                    && kind == other.kind
                    && Objects.equals(fn, other.fn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, tag, a, b, kind, fn);
        }

        static AV top(int s) { return new AV(s, Tag.TOP, null, null, null, null); }
        static AV bot(int s) { return new AV(s, Tag.BOT, null, null, null, null); }
        static AV cls(Sym c) { return new AV(1, Tag.CLASS, c, null, null, null); }
        static AV str(Sym s) { return new AV(1, Tag.STR, s, null, null, null); }
        static AV field(Sym c, Sym n) { return new AV(1, Tag.FIELD, c, n, null, null); }
        static AV fields(Sym c) { return new AV(1, Tag.FIELDS, c, null, null, null); }
        static AV callable(Fn f) { return new AV(1, Tag.CALLABLE, null, null, null, f); }
        static AV prod(Kind k, Sym c, Sym n, int size) { return new AV(size, Tag.PROD, c, n, k, null); }

        /** 由偏移量派生（加减、窄化、装箱）：保持绑定，换宽度。 */
        static AV derive(AV v, int size) {
            return v.is(Tag.PROD) ? new AV(size, Tag.PROD, v.a, v.b, v.kind, null) : top(size);
        }

        /** 方法返回 / 字段读取等"不知道具体值"的情形，按静态类型给出最有信息量的未知值。 */
        static AV unknownOf(Type t) {
            switch (t.getSort()) {
                case Type.VOID: return null;
                case Type.LONG: case Type.DOUBLE: return top(2);
                case Type.OBJECT:
                    switch (t.getInternalName()) {
                        case "java/lang/Class": return cls(Sym.UNK);
                        case "java/lang/String": return str(Sym.UNK);
                        case "java/lang/reflect/Field": return field(Sym.UNK, Sym.UNK);
                        default: return top(1);
                    }
                case Type.ARRAY:
                    return t.getDescriptor().equals("[Ljava/lang/reflect/Field;") ? fields(Sym.UNK) : top(1);
                default: return top(1);
            }
        }
    }

    /** 格子 join。BOT 是单位元；PROD 对其它值"吸收"（保守：宁可多报）。 */
    static AV mergeAV(AV x, AV y) {
        if (x.equals(y)) return x;
        if (x.is(Tag.BOT)) return y;
        if (y.is(Tag.BOT)) return x;
        if (x.tag() == y.tag()) {
            switch (x.tag()) {
                case CLASS:  return AV.cls(x.a().merge(y.a()));
                case STR:    return AV.str(x.a().merge(y.a()));
                case FIELD:  return AV.field(x.a().merge(y.a()), x.b().merge(y.b()));
                case FIELDS: return AV.fields(x.a().merge(y.a()));
                case PROD: {
                    Sym a = x.a().merge(y.a()), b = x.b().merge(y.b());
                    return AV.prod(x.kind(), a, b, Math.max(x.size(), y.size()));
                }
                default: break;
            }
        }
        if (x.is(Tag.PROD)) return AV.prod(x.kind(), Sym.UNK, Sym.UNK, x.size());
        if (y.is(Tag.PROD)) return AV.prod(y.kind(), Sym.UNK, Sym.UNK, y.size());
        return AV.top(Math.max(x.size(), y.size()));
    }

    /* ======================= 触发集 / 规则常量 ======================= */

    private static final String UNSAFE_SUN = "sun/misc/Unsafe";
    private static final String UNSAFE_JDK = "jdk/internal/misc/Unsafe";
    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
    private static final String AIFU = "java/util/concurrent/atomic/AtomicIntegerFieldUpdater";
    private static final String ALFU = "java/util/concurrent/atomic/AtomicLongFieldUpdater";
    private static final String ARFU = "java/util/concurrent/atomic/AtomicReferenceFieldUpdater";

    /** 常量池预过滤触发集。Lookup 单独处理（任何用 lambda 的类 InnerClasses 里都有它）。 */
    private static final Set<String> TRIGGERS = Set.of(
            UNSAFE_SUN, UNSAFE_JDK, AIFU, ALFU, ARFU,
            "java/lang/invoke/VarHandle", "java/lang/invoke/ConstantBootstraps");

    /** 把偏移量 / 句柄"当场消费"的 owner：参数里有 PROD 不算逃逸。 */
    private static final Set<String> CONSUMERS = Set.of(
            UNSAFE_SUN, UNSAFE_JDK, AIFU, ALFU, ARFU,
            "java/lang/invoke/VarHandle", "java/lang/invoke/MethodHandle");

    /** 无害接收者：日志 / 字符串拼接 / 装箱。可按需扩充。 */
    private static final Set<String> BENIGN = Set.of(
            "java/lang/StringBuilder", "java/lang/StringBuffer", "java/lang/String",
            "java/io/PrintStream", "java/lang/Long");

    private static boolean isJdk(String o) {
        return o.startsWith("java/") || o.startsWith("javax/") || o.startsWith("jdk/")
                || o.startsWith("sun/") || o.startsWith("com/sun/") || o.startsWith("[");
    }

    /* ======================= 扫描器状态 ======================= */

    private record MethodResult(AV ret, List<Binding> bindings) {
        static final MethodResult EMPTY = new MethodResult(null, List.of());
    }

    private final ClassSource src;
    private final Report rep = new Report();
    private final Map<String, ClassNode> nodes = new HashMap<>();
    private final Map<String, MethodResult> results = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();
    private final Set<String> staticsDone = new HashSet<>();
    private final Map<String, Map<String, AV>> statics = new HashMap<>();
    private final Set<String> producerClasses = new HashSet<>();
    private final Set<String> holderClasses = new HashSet<>();

    public OffsetScanner(ClassSource src) { this.src = src; }

    public Report report() { return rep; }
    public Set<String> producerClasses() { return Collections.unmodifiableSet(producerClasses); }
    public Set<String> holderClasses() { return Collections.unmodifiableSet(holderClasses); }

    /** 释放 ClassNode / 摘要缓存（大型应用扫描后调用）。Report 与 producerClasses 保留。 */
    public void clearCaches() { nodes.clear(); results.clear(); statics.clear(); staticsDone.clear(); }

    /* ======================= 入口 ======================= */

    /**
     * 批量扫描（启动补扫）。多轮：先扫命中内置触发集的类；其中返回偏移量的 helper 类
     * 登记为 producer，后续轮次把引用了 producer 的类也纳入，直至不动点。
     */
    public Report scanAll(Collection<String> classNames) {
        Set<String> pending = new LinkedHashSet<>(classNames);
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String n = it.next();
                byte[] b = src.bytes(n);
                if (b == null) { rep.unreadable.add(n); it.remove(); continue; }
                if (shouldScan(b)) {
                    scanBytes(b);
                    it.remove();
                    progress = true;
                }
            }
        }
        rep.skippedByPrefilter += pending.size();
        return rep;
    }

    /** 增量扫描单个类（ClassFileTransformer 里调用）。未通过预过滤返回空列表。 */
    public List<Binding> scan(byte[] classBytes) {
        return shouldScan(classBytes) ? scanBytes(classBytes) : List.of();
    }

    private boolean shouldScan(byte[] b) {
        Set<String> refs;
        try { refs = classRefs(b); } catch (RuntimeException e) { return false; }
        for (String r : refs) if (TRIGGERS.contains(r) || producerClasses.contains(r) || holderClasses.contains(r)) return true;
        if (refs.contains(LOOKUP)) {
            String raw = new String(b, StandardCharsets.ISO_8859_1);
            return raw.contains("VarHandle");
        }
        return false;
    }

    static Set<String> classRefs(byte[] b) {
        ClassReader cr = new ClassReader(b);
        char[] buf = new char[cr.getMaxStringLength()];
        Set<String> s = new HashSet<>();
        for (int i = 1; i < cr.getItemCount(); i++) {
            int off = cr.getItem(i);
            if (off > 0 && cr.readByte(off - 1) == 7 /* CONSTANT_Class */) s.add(cr.readUTF8(off, buf));
        }
        return s;
    }

    private List<Binding> scanBytes(byte[] b) {
        ClassNode cn = parse(b, "<bytes>");
        if (cn == null) return List.of();
        nodes.put(cn.name, cn);
        return scanNode(cn);
    }

    private List<Binding> scanNode(ClassNode cn) {
        rep.scannedClasses++;
        List<Binding> out = new ArrayList<>();
        ensureStatics(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (mn.instructions.size() == 0) continue;
            MethodResult r = result(cn, mn, 0);
            out.addAll(r.bindings());
            if (r.ret() != null && r.ret().is(Tag.PROD)) producerClasses.add(cn.name);
        }
        rep.bindings.addAll(out);
        return out;
    }

    /* ======================= 类 / 方法加载与缓存 ======================= */

    private ClassNode parse(byte[] b, String hint) {
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
            return cn;
        } catch (RuntimeException e) {
            rep.unreadable.add(hint + " (parse: " + e.getMessage() + ")");
            return null;
        }
    }

    private ClassNode classNode(String name) {
        if (nodes.containsKey(name)) return nodes.get(name);
        byte[] b = src.bytes(name);
        ClassNode cn = null;
        if (b == null) rep.unreadable.add(name); else cn = parse(b, name);
        nodes.put(name, cn);
        return cn;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    /** 先于其它方法分析 <clinit>，收集 static final 的 Class / String / Field 常量。 */
    private void ensureStatics(ClassNode cn, int depth) {
        if (!staticsDone.add(cn.name)) return;
        for (MethodNode mn : cn.methods)
            if (mn.name.equals("<clinit>") && mn.instructions.size() > 0) result(cn, mn, depth);
    }

    private AV staticOf(String owner, String name, int depth) {
        Map<String, AV> m = statics.get(owner);
        if (m == null && !isJdk(owner)) {
            ClassNode c = classNode(owner);
            if (c != null) { ensureStatics(c, depth + 1); m = statics.get(owner); }
        }
        return m == null ? null : m.get(name);
    }

    /** 被调方法的返回值摘要；找不到 / 超深 / 成环返回 null。 */
    private AV summaryRet(Fn fn, int depth) {
        if (isJdk(fn.owner())) return null;
        ClassNode c = classNode(fn.owner());
        if (c == null) return null;
        MethodNode m = findMethod(c, fn.name(), fn.desc());
        if (m == null || m.instructions.size() == 0) return null;
        return result(c, m, depth).ret();
    }

    /** 虚调用能否按声明类静态解析：同类 / private / final。覆盖匿名类 bridge 方法的常见情形。 */
    private boolean devirtualizable(String owner, String name, String desc, String currentClass) {
        if (owner.equals(currentClass)) return true;
        if (isJdk(owner)) return false;
        ClassNode c = classNode(owner);
        if (c == null) return false;
        if ((c.access & ACC_FINAL) != 0) return true;
        MethodNode m = findMethod(c, name, desc);
        return m != null && (m.access & (ACC_FINAL | ACC_PRIVATE)) != 0;
    }

    private MethodResult result(ClassNode cn, MethodNode mn, int depth) {
        String key = cn.name + "." + mn.name + mn.desc;
        MethodResult cached = results.get(key);
        if (cached != null) return cached;
        if (inProgress.contains(key)) { rep.cycles++; return MethodResult.EMPTY; }
        if (depth > MAX_DEPTH) { rep.depthExceeded++; return MethodResult.EMPTY; }
        inProgress.add(key);
        try {
            boolean clinit = mn.name.equals("<clinit>");
            if (!clinit) ensureStatics(cn, depth);
            MethodResult r = analyze(cn, mn, depth);
            if (clinit) r = analyze(cn, mn, depth);   // 第二遍：能看到第一遍收集的 static 常量
            results.put(key, r);
            return r;
        } finally {
            inProgress.remove(key);
        }
    }

    /* ======================= 单方法分析 + 后处理 ======================= */

    private MethodResult analyze(ClassNode cn, MethodNode mn, int depth) {
        Interp in = new Interp(cn, mn, depth);
        Analyzer<AV> an = new Analyzer<>(in);
        Frame<AV>[] fr;
        try {
            an.analyze(cn.name, mn);
            fr = an.getFrames();
        } catch (AnalyzerException | RuntimeException e) {
            rep.analysisFailed.add(cn.name + "." + mn.name + mn.desc + " : " + e.getMessage());
            return MethodResult.EMPTY;
        }

        AbstractInsnNode[] insns = mn.instructions.toArray();
        Set<Binding> out = new LinkedHashSet<>();
        AV ret = null;
        boolean clinit = mn.name.equals("<clinit>");

        for (int i = 0; i < insns.length; i++) {
            Frame<AV> f = fr[i];
            if (f == null) continue;                       // 不可达
            AbstractInsnNode insn = insns[i];
            switch (insn.getOpcode()) {
                case GETSTATIC -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    if (!isJdk(fi.owner)) {
                        rep.fieldReaders.computeIfAbsent(fi.owner + "." + fi.name, k -> new LinkedHashSet<>()).add(cn.name);
                    }
                }
                case PUTSTATIC -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    AV v = peek(f, 0);
                    if (v.is(Tag.PROD)) {
                        out.add(mk(v, Sink.STATIC_FIELD, cn, mn, insn, fi.owner + "." + fi.name));
                        if (!isJdk(fi.owner)) holderClasses.add(fi.owner);
                    }
                    else if (clinit && isFinalOwn(cn, fi) && keepable(v))
                        statics.computeIfAbsent(fi.owner, k -> new HashMap<>()).put(fi.name, v);
                }
                case PUTFIELD -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    AV v = peek(f, 0);
                    if (v.is(Tag.PROD)) out.add(mk(v, Sink.INSTANCE_FIELD, cn, mn, insn, fi.owner + "." + fi.name));
                }
                case IASTORE, LASTORE, FASTORE, DASTORE, AASTORE, BASTORE, CASTORE, SASTORE -> {
                    AV v = peek(f, 0);
                    if (v.is(Tag.PROD)) out.add(mk(v, Sink.ESCAPED, cn, mn, insn, "<array>"));
                }
                case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE -> {
                    MethodInsnNode m = (MethodInsnNode) insn;
                    if (CONSUMERS.contains(m.owner) || BENIGN.contains(m.owner)) break;
                    int n = Type.getArgumentTypes(m.desc).length + (insn.getOpcode() == INVOKESTATIC ? 0 : 1);
                    for (int k = 0; k < n; k++) {
                        AV v = peek(f, k);
                        if (v.is(Tag.PROD)) out.add(mk(v, Sink.ESCAPED, cn, mn, insn, m.owner + "." + m.name));
                    }
                }
                case INVOKEDYNAMIC -> {
                    InvokeDynamicInsnNode d = (InvokeDynamicInsnNode) insn;
                    if (d.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) break;
                    int n = Type.getArgumentTypes(d.desc).length;
                    for (int k = 0; k < n; k++) {
                        AV v = peek(f, k);
                        if (v.is(Tag.PROD)) out.add(mk(v, Sink.ESCAPED, cn, mn, insn, "<indy>" + d.name));
                    }
                }
                case ARETURN, LRETURN, IRETURN -> {
                    AV v = peek(f, 0);
                    ret = ret == null ? v : mergeAV(ret, v);
                }
                default -> { }
            }
        }
        return new MethodResult(ret, new ArrayList<>(out));
    }

    private static AV peek(Frame<AV> f, int k) { return f.getStack(f.getStackSize() - 1 - k); }

    private static boolean keepable(AV v) {
        return v.is(Tag.CLASS) || v.is(Tag.STR) || v.is(Tag.FIELD) || v.is(Tag.FIELDS);
    }

    /** 只信任本类的 static final：非 final 的静态字段可能被别处改写。 */
    private static boolean isFinalOwn(ClassNode cn, FieldInsnNode fi) {
        if (!fi.owner.equals(cn.name)) return false;
        for (FieldNode f : cn.fields) if (f.name.equals(fi.name)) return (f.access & ACC_FINAL) != 0;
        return false;
    }

    private static int lineOf(AbstractInsnNode n) {
        for (AbstractInsnNode p = n; p != null; p = p.getPrevious())
            if (p instanceof LineNumberNode l) return l.line;
        return -1;
    }

    private Binding mk(AV v, Sink sink, ClassNode cn, MethodNode mn, AbstractInsnNode at, String member) {
        String tc = v.a() != null && v.a().isConst() ? v.a().v().replace('.', '/') : null;
        String tf = v.b() != null && v.b().isConst() ? v.b().v() : null;
        Confidence c = tc == null ? Confidence.UNRESOLVED_CLASS : tf == null ? Confidence.WILDCARD_FIELD : Confidence.EXACT;
        return new Binding(tc, tf, v.kind(), sink, cn.name, member, cn.name + "." + mn.name + ":" + lineOf(at), c);
    }

    /* ======================= 符号辅助 ======================= */

    private static Sym clsSym(AV v) { return v != null && v.is(Tag.CLASS) ? v.a() : Sym.UNK; }
    private static Sym strSym(AV v) { return v != null && v.is(Tag.STR) ? v.a() : Sym.UNK; }

    private static Sym norm(Sym s) { return s.isConst() ? Sym.c(s.v().replace('.', '/')) : s; }

    /** 字符串符号 → 类名符号：常量把 '.' 换成 '/'；实参占位换成 'S'（代入时再规范化）。 */
    private static Sym asClassName(Sym s) { return s.k() == 'P' ? new Sym('S', null, s.p()) : norm(s); }

    private static AV arg(List<? extends AV> args, int i) { return i >= 0 && i < args.size() ? args.get(i) : null; }

    /** 在符号 s 中代入实参。cr: 该槽位的角色是否为"类"（否则为"字符串/字段名"）。 */
    private static Sym sub(Sym s, List<? extends AV> args, boolean cr) {
        if (s == null) return null;
        AV x;
        switch (s.k()) {
            case 'P': x = arg(args, s.p()); return x == null ? Sym.UNK : cr ? clsSym(x) : strSym(x);
            case 'S': x = arg(args, s.p()); return x == null ? Sym.UNK : norm(strSym(x));
            case 'Q': x = arg(args, s.p()); return x != null && x.is(Tag.FIELD) ? x.a() : Sym.UNK;
            case 'R': x = arg(args, s.p()); return x != null && x.is(Tag.FIELD) ? x.b() : Sym.UNK;
            default: return s;
        }
    }

    private static AV subst(AV r, List<? extends AV> args) {
        switch (r.tag()) {
            case CLASS:  return AV.cls(sub(r.a(), args, true));
            case STR:    return AV.str(sub(r.a(), args, false));
            case FIELD:  return AV.field(sub(r.a(), args, true), sub(r.b(), args, false));
            case FIELDS: return AV.fields(sub(r.a(), args, true));
            case PROD:   return AV.prod(r.kind(), sub(r.a(), args, true), sub(r.b(), args, false), r.size());
            default:     return r;
        }
    }

    private static AV fieldToProd(Kind k, AV f, int size) {
        return f != null && f.is(Tag.FIELD) ? AV.prod(k, f.a(), f.b(), size) : AV.prod(k, Sym.UNK, Sym.UNK, size);
    }

    /* ======================= Interpreter ======================= */

    private final class Interp extends Interpreter<AV> {
        final ClassNode cn;
        final int depth;
        final int[] slotToArg;

        Interp(ClassNode cn, MethodNode mn, int depth) {
            super(ASM9);
            this.cn = cn;
            this.depth = depth;
            slotToArg = new int[Math.max(mn.maxLocals, 1)];
            Arrays.fill(slotToArg, -1);
            int slot = (mn.access & ACC_STATIC) != 0 ? 0 : 1;
            Type[] as = Type.getArgumentTypes(mn.desc);
            for (int i = 0; i < as.length; i++) {
                if (slot < slotToArg.length) slotToArg[slot] = i;
                slot += as[i].getSize();
            }
        }

        /* ---- 初始值 ---- */

        @Override public AV newValue(Type t) {
            if (t == null) return AV.bot(1);          // 未初始化局部变量：真正的 bottom
            return AV.unknownOf(t);
        }

        @Override public AV newParameterValue(boolean isInstance, int local, Type t) {
            int idx = local < slotToArg.length ? slotToArg[local] : -1;
            if (idx < 0 || t.getSort() < Type.ARRAY) return AV.unknownOf(t);
            if (t.getSort() == Type.OBJECT) {
                switch (t.getInternalName()) {
                    case "java/lang/Class":  return AV.cls(new Sym('P', null, idx));
                    case "java/lang/String": return AV.str(new Sym('P', null, idx));
                    case "java/lang/reflect/Field":
                        return AV.field(new Sym('Q', null, idx), new Sym('R', null, idx));
                    default: return AV.top(1);
                }
            }
            return AV.unknownOf(t);
        }

        /* ---- 无输入指令 ---- */

        @Override public AV newOperation(AbstractInsnNode insn) {
            switch (insn.getOpcode()) {
                case ACONST_NULL, ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5,
                     FCONST_0, FCONST_1, FCONST_2, BIPUSH, SIPUSH:
                    return AV.bot(1);
                case LCONST_0, LCONST_1, DCONST_0, DCONST_1:
                    return AV.bot(2);
                case LDC:
                    return ldc(((LdcInsnNode) insn).cst);
                case JSR:
                    return AV.top(1);
                case GETSTATIC: {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    Type t = Type.getType(f.desc);
                    AV u = AV.unknownOf(t);
                    if (u.tag() != Tag.TOP) {            // 仅 Class / String / Field / Field[] 值得追
                        AV v = staticOf(f.owner, f.name, depth);
                        if (v != null) return v;
                    }
                    return u;
                }
                case NEW:   // 为 doPrivileged(new X(){...}) 保留：run() 即回调
                    return AV.callable(new Fn(((TypeInsnNode) insn).desc, "run", "()Ljava/lang/Object;"));
                default:
                    throw new IllegalArgumentException("newOperation " + insn.getOpcode());
            }
        }

        private AV ldc(Object c) {
            if (c instanceof Type t) {
                if (t.getSort() == Type.METHOD) return AV.top(1);
                return AV.cls(Sym.c(t.getSort() == Type.OBJECT ? t.getInternalName() : t.getDescriptor()));
            }
            if (c instanceof String s) return AV.str(Sym.c(s));
            if (c instanceof Long || c instanceof Double) return AV.bot(2);
            if (c instanceof Integer || c instanceof Float) return AV.bot(1);
            if (c instanceof ConstantDynamic cd) {
                Handle bsm = cd.getBootstrapMethod();
                if (bsm.getOwner().equals("java/lang/invoke/ConstantBootstraps")
                        && (bsm.getName().equals("fieldVarHandle") || bsm.getName().equals("staticFieldVarHandle"))
                        && cd.getBootstrapMethodArgumentCount() >= 2
                        && cd.getBootstrapMethodArgument(0) instanceof Type k
                        && cd.getBootstrapMethodArgument(1) instanceof String fname) {
                    return AV.prod(Kind.VARHANDLE, Sym.c(k.getInternalName()), Sym.c(fname), 1);
                }
                return AV.unknownOf(Type.getType(cd.getDescriptor()));
            }
            return AV.top(1);
        }

        /* ---- 拷贝类（xLOAD/xSTORE/DUP/SWAP/POP...）：原样透传 ---- */

        @Override public AV copyOperation(AbstractInsnNode insn, AV v) { return v; }

        /* ---- 一元 ---- */

        @Override public AV unaryOperation(AbstractInsnNode insn, AV v) {
            switch (insn.getOpcode()) {
                case INEG, IINC, F2I, D2I, I2B, I2C, I2S, ARRAYLENGTH, INSTANCEOF, NEWARRAY, ANEWARRAY:
                    return AV.top(1);
                case L2I: return AV.derive(v, 1);
                case I2L: return AV.derive(v, 2);
                case FNEG, I2F, L2F, D2F: return AV.top(1);
                case LNEG, F2L, D2L, DNEG, I2D, L2D, F2D: return AV.top(2);
                case GETFIELD: return AV.unknownOf(Type.getType(((FieldInsnNode) insn).desc));
                case CHECKCAST:   // 保值；若原值未知则按目标类型给出"类型化未知"
                    return v.is(Tag.TOP) ? AV.unknownOf(Type.getObjectType(((TypeInsnNode) insn).desc)) : v;
                // 无结果（Frame 不会 push）
                case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL, TABLESWITCH, LOOKUPSWITCH,
                     IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, PUTSTATIC, ATHROW, MONITORENTER, MONITOREXIT:
                    return null;
                default:
                    throw new IllegalArgumentException("unary " + insn.getOpcode());
            }
        }

        /* ---- 二元 ---- */

        @Override public AV binaryOperation(AbstractInsnNode insn, AV a, AV b) {
            switch (insn.getOpcode()) {
                case IALOAD, BALOAD, CALOAD, SALOAD, IMUL, IDIV, IREM, ISHL, ISHR, IUSHR, IAND, IOR, IXOR,
                     FALOAD, FADD, FSUB, FMUL, FDIV, FREM,
                     LCMP, FCMPL, FCMPG, DCMPL, DCMPG:
                    return AV.top(1);
                case IADD, ISUB: return a.is(Tag.PROD) ? AV.derive(a, 1) : AV.derive(b, 1);
                case LADD, LSUB, LOR: return a.is(Tag.PROD) ? AV.derive(a, 2) : AV.derive(b, 2);
                case LALOAD, LMUL, LDIV, LREM, LSHL, LSHR, LUSHR, LAND, LXOR,
                     DALOAD, DADD, DSUB, DMUL, DDIV, DREM:
                    return AV.top(2);
                case AALOAD:   // Field[] 取元素 → 该类的某个字段（名未知）
                    return a.is(Tag.FIELDS) ? AV.field(a.a(), Sym.UNK) : AV.top(1);
                case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE, IF_ACMPEQ, IF_ACMPNE, PUTFIELD:
                    return null;
                default:
                    throw new IllegalArgumentException("binary " + insn.getOpcode());
            }
        }

        @Override public AV ternaryOperation(AbstractInsnNode insn, AV a, AV b, AV c) { return null; }

        /* ---- 调用 ---- */

        @Override public AV naryOperation(AbstractInsnNode insn, List<? extends AV> vs) {
            switch (insn.getOpcode()) {
                case MULTIANEWARRAY: return AV.top(1);
                case INVOKEDYNAMIC:  return indy((InvokeDynamicInsnNode) insn);
                default:             return invoke((MethodInsnNode) insn, vs);
            }
        }

        private AV indy(InvokeDynamicInsnNode d) {
            if (d.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    && d.bsmArgs.length >= 2 && d.bsmArgs[1] instanceof Handle impl) {
                return AV.callable(new Fn(impl.getOwner(), impl.getName(), impl.getDesc()));
            }
            return AV.unknownOf(Type.getReturnType(d.desc));
        }

        private AV invoke(MethodInsnNode m, List<? extends AV> vs) {
            final int op = m.getOpcode();
            final boolean stat = op == INVOKESTATIC;
            final List<? extends AV> a = stat ? vs : vs.subList(1, vs.size());
            final AV recv = stat ? null : vs.get(0);
            final Type ret = Type.getReturnType(m.desc);
            final String o = m.owner, n = m.name;

            /* 1. Class / Field 来源 */
            if (o.equals("java/lang/Class")) {
                switch (n) {
                    case "forName":
                        if (!a.isEmpty()) return AV.cls(asClassName(strSym(a.get(0))));
                        break;
                    case "getDeclaredField", "getField":
                        return AV.field(clsSym(recv), strSym(a.get(0)));
                    case "getDeclaredFields", "getFields":
                        return AV.fields(clsSym(recv));
                    default: break;
                }
            }
            if (n.equals("loadClass") && m.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                return AV.cls(asClassName(strSym(a.get(0))));
            }

            /* 2. Unsafe 偏移量 */
            if (o.equals(UNSAFE_SUN) || o.equals(UNSAFE_JDK)) {
                switch (n) {
                    case "objectFieldOffset":
                        if (m.desc.startsWith("(Ljava/lang/reflect/Field;)")) return fieldToProd(Kind.INSTANCE_OFFSET, a.get(0), 2);
                        if (m.desc.startsWith("(Ljava/lang/Class;Ljava/lang/String;)"))
                            return AV.prod(Kind.INSTANCE_OFFSET, clsSym(a.get(0)), strSym(a.get(1)), 2);
                        break;
                    case "staticFieldOffset":
                        return fieldToProd(Kind.STATIC_OFFSET, a.get(0), 2);
                    case "staticFieldBase":
                        return fieldToProd(Kind.STATIC_BASE, a.get(0), 1);
                    default: break;
                }
                return AV.unknownOf(ret);
            }

            /* 3. AtomicXxxFieldUpdater.newUpdater —— 字段名永远是最后一个参数 */
            if (n.equals("newUpdater") && (o.equals(AIFU) || o.equals(ALFU) || o.equals(ARFU))) {
                return AV.prod(Kind.UPDATER, clsSym(a.get(0)), strSym(a.get(a.size() - 1)), 1);
            }

            /* 4. MethodHandles.Lookup 句柄工厂（receiver 是 Lookup，参数已剥离）
             * 注意：实测证明 MethodHandle 的 getter/setter (findGetter/unreflectGetter 等)
             * 在 DCEVM 下随版本感知自动重定向自愈（见 OffsetStabilityProbe E2），
             * 不是偏移漂移的受害者，严禁将其抓入 Binding，避免误杀 Jackson/Record 等现代框架。
             * 此处仅拦截缺乏自愈能力、漂移后产生内存污染的 VarHandle。 */
            if (o.equals(LOOKUP)) {
                switch (n) {
                    case "findVarHandle", "findStaticVarHandle":
                        return AV.prod(Kind.VARHANDLE, clsSym(a.get(0)), strSym(a.get(1)), 1);
                    case "unreflectVarHandle":
                        return fieldToProd(Kind.VARHANDLE, a.get(0), 1);
                    default: break;
                }
            }

            /* 5. 装箱透传 */
            if (o.equals("java/lang/Long")) {
                if (n.equals("valueOf") && !a.isEmpty() && a.get(0).is(Tag.PROD)) return AV.derive(a.get(0), 1);
                if (n.equals("longValue") && recv != null && recv.is(Tag.PROD)) return AV.derive(recv, 2);
            }

            /* 6. doPrivileged(回调)：用回调方法摘要，捕获变量一律当未知 */
            if (o.equals("java/security/AccessController") && n.equals("doPrivileged")
                    && !a.isEmpty() && a.get(0).is(Tag.CALLABLE)) {
                AV r = summaryRet(a.get(0).fn(), depth + 1);
                return r == null ? AV.unknownOf(ret) : subst(r, List.of());
            }

            /* 7. 项目内 helper：代入摘要 */
            if (!isJdk(o) && !n.equals("<init>")) {
                boolean resolvable = stat || op == INVOKESPECIAL || devirtualizable(o, n, m.desc, cn.name);
                if (resolvable) {
                    AV r = summaryRet(new Fn(o, n, m.desc), depth + 1);
                    if (r != null) return subst(r, a);
                }
            }

            return AV.unknownOf(ret);
        }

        @Override public void returnOperation(AbstractInsnNode insn, AV v, AV expected) { /* 后处理统一处理 */ }

        @Override public AV merge(AV x, AV y) { return mergeAV(x, y); }
    }
}
