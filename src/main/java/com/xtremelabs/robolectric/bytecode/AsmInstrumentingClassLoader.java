package com.xtremelabs.robolectric.bytecode;


import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.util.*;

import static org.objectweb.asm.Type.getType;

public class AsmInstrumentingClassLoader extends ClassLoader implements Opcodes, InstrumentingClassLoader {
    private static final String OBJECT_DESC = Type.getDescriptor(Object.class);
    private static final Type OBJECT_TYPE = getType(Object.class);
    private static final Type STRING_TYPE = getType(String.class);
    private static final Type ROBOLECTRIC_INTERNALS_TYPE = Type.getType(RobolectricInternals.class);

    private static boolean debug = false;

    private final Setup setup;
    private final Map<String, Class> classes = new HashMap<String, Class>();

    public AsmInstrumentingClassLoader(Setup setup, ClassLoader classLoader) {
        super(classLoader);
        this.setup = setup;
    }

    @Override
    synchronized public Class loadClass(String name) throws ClassNotFoundException {
        System.out.println("loadClass " + name);
        Class<?> theClass = classes.get(name);
        if (theClass != null) return theClass;

        boolean shouldComeFromThisClassLoader = setup.shouldAcquire(name);

        if (shouldComeFromThisClassLoader) {
            theClass = findClass(name);
        } else {
            theClass = super.loadClass(name);
        }

        classes.put(name, theClass);
        return theClass;
    }

    @Override
    protected Class<?> findClass(final String className) throws ClassNotFoundException {
        if (setup.shouldAcquire(className)) {
            InputStream classBytesStream = getResourceAsStream(className.replace('.', '/') + ".class");

            if (classBytesStream == null) throw new ClassNotFoundException(className);

            Class<?> originalClass = super.loadClass(className);
            try {
                if (setup.shouldInstrument(originalClass)) {
                    byte[] bytes = getInstrumentedBytes(className, classBytesStream);
                    return defineClass(className, bytes, 0, bytes.length);
                } else {
                    byte[] bytes = readBytes(classBytesStream);
                    return defineClass(className, bytes, 0, bytes.length);
                }
            } catch (IOException e) {
                throw new ClassNotFoundException("couldn't load " + className, e);
            }
        } else {
            throw new IllegalStateException();
//            return super.findClass(className);
        }
    }

    private byte[] getInstrumentedBytes(String className, InputStream classBytesStream) throws IOException {
        final ClassReader classReader = new ClassReader(classBytesStream);
        ClassNode classNode = new ClassNode();
        classReader.accept(classNode, 0);

        List<MethodNode> methods = new ArrayList<MethodNode>(classNode.methods);
        for (MethodNode method : methods) {
            String originalName = method.name;
            if (!method.name.startsWith("<")) {
                String directName = MethodGenerator.directMethodName(className, method.name);
                method.name = directName;
                classNode.methods.add(generateInstrumentedMethod(className, method, originalName));
            } else if (method.name.equals("<init>")) {
                convertConstructorToRegularMethod(method);
                method.name = CONSTRUCTOR_METHOD_NAME;
                classNode.methods.add(generateConstructorMethod(className, method));
            } else if (method.name.equals("<clinit>")) {
                System.out.println("originalName = " + originalName);
                convertConstructorToRegularMethod(method);
                method.name = STATIC_INITIALIZER_METHOD_NAME;
                classNode.methods.add(generateStaticInitializerNotifierMethod(className));
            }
        }

        classNode.fields.add(new FieldNode(Modifier.PUBLIC, CLASS_HANDLER_DATA_FIELD_NAME, OBJECT_DESC, OBJECT_DESC, null));

        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(classWriter);

        byte[] classBytes = classWriter.toByteArray();
        if (debug || className.equals("android.webkit.TestWebSettingsTest")) {
            new ClassReader(classBytes).accept(new TraceClassVisitor(new PrintWriter(System.out)), 0);
        }
        return classBytes;
    }

    private void convertConstructorToRegularMethod(MethodNode cons) {
        InsnList ins = cons.instructions;
        ListIterator li = ins.iterator();
        // Look for the ALOAD 0 (i.e., push this on the stack)
        while (li.hasNext()) {
            AbstractInsnNode node = (AbstractInsnNode) li.next();
            if (node.getOpcode() == ALOAD) {
                VarInsnNode varNode = (VarInsnNode) node;
                assert varNode.var == 0;
                // Remove the ALOAD
                li.remove();
                break;
            }
        }

        // Look for the call to the super-class, an INVOKESPECIAL
        while (li.hasNext()) {
            AbstractInsnNode node = (AbstractInsnNode) li.next();
            if (node.getOpcode() == INVOKESPECIAL) {
                MethodInsnNode mnode = (MethodInsnNode) node;
//                assert mnode.owner.equals(methodNo.superName);
                assert mnode.name.equals("<init>");
                assert mnode.desc.equals(cons.desc);

                li.remove();
                return;
            }
        }

        throw new AssertionError("Could not convert constructor to simple method.");
    }

    private MethodNode generateInstrumentedMethod(String className, MethodNode directMethod, String originalName) {
        String[] exceptions = ((List<String>) directMethod.exceptions).toArray(new String[directMethod.exceptions.size()]);
        MethodNode methodNode = new MethodNode(directMethod.access, originalName, directMethod.desc, directMethod.signature, exceptions);
        methodNode.access &= ~(Modifier.NATIVE | Modifier.ABSTRACT);

        MyGeneratorAdapter m = new MyGeneratorAdapter(methodNode, directMethod, originalName);
        String classRef = classRef(className);
        Type classType = getType(classRef);
        Type returnType = Type.getReturnType(directMethod.desc);

        Label callDirect = new Label();
        Label callClassHandler = new Label();

        m.visitCode();

        if (!m.isStatic) {
            m.loadThis();                                         // this
            m.getField(classType, "__robo_data__", OBJECT_TYPE);   // contents of __robo_data__
            m.instanceOf(classType);                              // is instance of same class?
            m.visitJumpInsn(IFNE, callDirect); // jump if yes (is instance)
        }

        m.loadThisOrNot();                                        // this
        m.invokeStatic(ROBOLECTRIC_INTERNALS_TYPE, new Method("shouldCallDirectly", "(Ljava/lang/Object;)Z"));
        // args, should call directly?
        m.visitJumpInsn(IFEQ, callClassHandler); // jump if no (should not call directly)

        // callDirect...
        m.mark(callDirect);

        // call direct method and return
        m.loadThisOrNot();                                        // this
        m.loadArgs();                                             // this, [args]
        m.visitMethodInsn(INVOKESPECIAL, classRef, originalName, directMethod.desc);
        m.returnValue();

        // callClassHandler...
        m.mark(callClassHandler);

        // prepare for call to classHandler.methodInvoked()
        m.loadThisOrNot();                                        // this
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;");
        // my class
        m.push(originalName);                                     // my class, method name
        m.loadThisOrNot();                                        // my class, method name, this
//
//            // load param types
        Type[] argumentTypes = Type.getArgumentTypes(directMethod.desc);
        m.push(argumentTypes.length);
        m.newArray(STRING_TYPE);                                   // my class, method name, this, String[n]{nulls}
        for (int i = 0; i < argumentTypes.length; i++) {
            Type argumentType = argumentTypes[i];
            m.dup();
            m.push(i);
            m.push(argumentType.getClassName());
            m.arrayStore(STRING_TYPE);
        }
        // my class, method name, this, String[n]{param class names}

        m.loadArgArray();

        m.invokeStatic(ROBOLECTRIC_INTERNALS_TYPE, new Method("methodInvoked", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
        m.unbox(returnType);
        m.returnValue();

        m.endMethod();

        return methodNode;
    }

    private MethodNode generateStaticInitializerNotifierMethod(String className) {
        MethodNode methodNode = new MethodNode(Modifier.STATIC, "<clinit>", "()V", "()V", null);
        GeneratorAdapter m = new GeneratorAdapter(methodNode, Modifier.STATIC, "<clinit>", "()V");
        m.visitCode();
        m.push(Type.getObjectType(classRef(className)));
        m.invokeStatic(Type.getType(RobolectricInternals.class), new Method("classInitializing", "(Ljava/lang/Class;)V"));
        m.returnValue();
        m.endMethod();
        return methodNode;
    }

    private MethodNode generateConstructorMethod(String className, MethodNode directMethod) {
        String[] exceptions = ((List<String>) directMethod.exceptions).toArray(new String[directMethod.exceptions.size()]);
        MethodNode methodNode = new MethodNode(directMethod.access, "<init>", directMethod.desc, directMethod.signature, exceptions);
        MyGeneratorAdapter m = new MyGeneratorAdapter(methodNode, directMethod, "<init>");
        // prepare for call to classHandler.methodInvoked()
//        m.loadThis();                                             // this
//        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;");
//        // my class
//        m.push(CONSTRUCTOR_METHOD_NAME);                          // my class, method name
//        m.loadThis();                                             // my class, method name, this
////
////            // load param types
//        Type[] argumentTypes = Type.getArgumentTypes(directMethod.desc);
//        m.push(argumentTypes.length);
//        m.newArray(STRING_TYPE);                                  // my class, method name, this, String[n]{nulls}
//        for (int i = 0; i < argumentTypes.length; i++) {
//            Type argumentType = argumentTypes[i];
//            m.dup();
//            m.push(i);
//            m.push(argumentType.getClassName());
//            m.arrayStore(STRING_TYPE);
//        }
//        // my class, method name, this, String[n]{param class names}
//
//        m.loadArgArray();
//
//        m.invokeStatic(ROBOLECTRIC_INTERNALS_TYPE, new Method("methodInvoked", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"));
        m.returnValue();

        m.endMethod();
        return methodNode;
    }

    private String classRef(String className) {
        return className.replace('.', '/');
    }

    private static byte[] readBytes(InputStream classBytesStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int c;
        while ((c = classBytesStream.read()) != -1) {
            baos.write(c);
        }
        return baos.toByteArray();
    }

    private static class MyGeneratorAdapter extends GeneratorAdapter {
        private final boolean isStatic;

        public MyGeneratorAdapter(MethodNode methodNode, MethodNode directMethod, String originalName) {
            super(methodNode, directMethod.access, originalName, directMethod.desc);
            this.isStatic = Modifier.isStatic(directMethod.access);
        }

        public void loadThisOrNot() {
            if (isStatic) {
                loadNull();
            } else {
                loadThis();
            }
        }

        public boolean isStatic() {
            return isStatic;
        }

        private void loadNull() {
            visitInsn(ACONST_NULL);
        }
    }
}