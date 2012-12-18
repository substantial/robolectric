package com.xtremelabs.robolectric.bytecode;

import com.xtremelabs.robolectric.internal.DoNotInstrument;
import com.xtremelabs.robolectric.internal.Instrument;
import com.xtremelabs.robolectric.util.Transcript;
import javassist.CannotCompileException;
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.Loader;
import javassist.NotFoundException;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.*;

public class InstrumentingClassLoaderTest {

    private ClassLoader classLoader;
    private MyClassHandler classHandler;
    private Class<?> exampleClass;
    private Transcript transcript = new Transcript();

    @Before
    public void setUp() throws Exception {
//        transformWithJavassist();
        classLoader = transformWithAsm();
        classHandler = new MyClassHandler(transcript);
        injectClassHandler(classLoader, classHandler);
        exampleClass = loadClass(ExampleClass.class);
    }

    private ClassLoader transformWithJavassist() throws NotFoundException, CannotCompileException, ClassNotFoundException {
        ClassPool cp = new ClassPool();
        cp.appendClassPath(new ClassClassPath(getClass()));
        Loader loader = new Loader();
        loader.delegateLoadingOf(ClassHandler.class.getName());
        loader.addTranslator(cp, new AndroidTranslator(new ZipClassCache("tmp/zzz.jar", -1), new Setup()));
        return loader;

    }

    private ClassLoader transformWithAsm() throws ClassNotFoundException {
        return new AsmInstrumentingClassLoader(new Setup(), getClass().getClassLoader());
    }

    @Test public void shouldAddDefaultConstructorIfMissing() throws Exception {
        Constructor<?> defaultCtor = loadClass(ClassWithNoDefaultConstructor.class).getConstructor();
        assertTrue(Modifier.isPublic(defaultCtor.getModifiers()));
        defaultCtor.setAccessible(true);
        defaultCtor.newInstance();
        transcript.assertNoEventsSoFar();
    }

    @SuppressWarnings("UnusedDeclaration")
    @Instrument static class ClassWithNoDefaultConstructor {
        private String name;

        ClassWithNoDefaultConstructor(String name) {
            this.name = name;
        }
    }

    @Test public void shouldDelegateToHandlerForConstructors() throws Exception {
        Class<?> clazz = loadClass(ClassWithNoDefaultConstructor.class);
        Constructor<?> ctor = clazz.getDeclaredConstructor(String.class);
        assertTrue(Modifier.isPublic(ctor.getModifiers()));
        ctor.setAccessible(true);
        Object instance = ctor.newInstance("new one");
        transcript.assertEventsSoFar("methodInvoked: ClassWithNoDefaultConstructor.__constructor__(java.lang.String new one)");

        Field nameField = clazz.getDeclaredField("name");
        nameField.setAccessible(true);
        assertNull(nameField.get(instance));
    }

    @Test public void shouldDelegateClassLoadForUnacquiredClasses() throws Exception {
        ClassLoader classTransformer = new AsmInstrumentingClassLoader(new MySetup(false, false), getClass().getClassLoader());
        Class<?> exampleClass = classTransformer.loadClass(ExampleClass.class.getName());
        assertSame(getClass().getClassLoader(), exampleClass.getClassLoader());
    }

    @Test public void shouldPerformClassLoadForAcquiredClasses() throws Exception {
        ClassLoader classTransformer = new AsmInstrumentingClassLoader(new MySetup(true, false), getClass().getClassLoader());
        Class<?> exampleClass = classTransformer.loadClass(ExampleClass.class.getName());
        assertSame(classTransformer, exampleClass.getClassLoader());
        try {
            exampleClass.getField(AsmInstrumentingClassLoader.CLASS_HANDLER_DATA_FIELD_NAME);
            fail("class shouldn't be instrumented!");
        } catch (Exception e) {
            // expected
        }
    }

    @Test public void shouldPerformClassLoadAndInstrumentLoadForInstrumentedClasses() throws Exception {
        ClassLoader classTransformer = new AsmInstrumentingClassLoader(new MySetup(true, true), getClass().getClassLoader());
        Class<?> exampleClass = classTransformer.loadClass(ExampleClass.class.getName());
        assertSame(classTransformer, exampleClass.getClassLoader());
        assertNotNull(exampleClass.getField(AsmInstrumentingClassLoader.CLASS_HANDLER_DATA_FIELD_NAME));
    }

    @Test
    public void callingNormalMethodShouldInvokeClassHandler() throws Exception {
        Method normalMethod = exampleClass.getMethod("normalMethod", String.class, int.class);

        Object exampleInstance = exampleClass.newInstance();
        assertEquals("response from methodInvoked: ExampleClass.normalMethod(java.lang.String value1, int 123)",
                normalMethod.invoke(exampleInstance, "value1", 123));
        transcript.assertEventsSoFar("methodInvoked: ExampleClass.__constructor__()",
                "methodInvoked: ExampleClass.normalMethod(java.lang.String value1, int 123)");
    }

    @Test
    public void callingNormalMethodReturningPrimitiveShouldInvokeClassHandler() throws Exception {
        classHandler.valueToReturn = 456;

        Method normalMethod = exampleClass.getMethod("normalMethodReturningPrimitive", int.class);
        Object exampleInstance = exampleClass.newInstance();
        assertEquals(456, normalMethod.invoke(exampleInstance, 123));
        transcript.assertEventsSoFar("methodInvoked: ExampleClass.__constructor__()",
                "methodInvoked: ExampleClass.normalMethodReturningPrimitive(int 123)");
    }

    @Test
    public void whenClassHandlerReturnsNull_callingNormalMethodReturningPrimitiveShouldWork() throws Exception {
        classHandler.valueToReturn = null;

        Method normalMethod = exampleClass.getMethod("normalMethodReturningPrimitive", int.class);
        Object exampleInstance = exampleClass.newInstance();
        assertEquals(0, normalMethod.invoke(exampleInstance, 123));
        transcript.assertEventsSoFar("methodInvoked: ExampleClass.__constructor__()",
                "methodInvoked: ExampleClass.normalMethodReturningPrimitive(int 123)");
    }

    @Test
    public void callingNativeMethodShouldInvokeClassHandler() throws Exception {
        Method normalMethod = exampleClass.getDeclaredMethod("nativeMethod", String.class, int.class);
        Object exampleInstance = exampleClass.newInstance();
        assertEquals("response from methodInvoked: ExampleClass.nativeMethod(java.lang.String value1, int 123)",
                normalMethod.invoke(exampleInstance, "value1", 123));
        transcript.assertEventsSoFar("methodInvoked: ExampleClass.__constructor__()",
                "methodInvoked: ExampleClass.nativeMethod(java.lang.String value1, int 123)");
    }

    @Test public void shouldGenerateClassSpecificDirectAccessMethod() throws Exception {
        String methodName = RobolectricInternals.directMethodName(ExampleClass.class.getName(), "normalMethod");
        Method directMethod = exampleClass.getDeclaredMethod(methodName, String.class, int.class);
        directMethod.setAccessible(true);
        Object exampleInstance = exampleClass.newInstance();
        assertEquals("normalMethod(value1, 123)", directMethod.invoke(exampleInstance, "value1", 123));
        transcript.assertEventsSoFar("methodInvoked: ExampleClass.__constructor__()");
    }

    private static void injectClassHandler(ClassLoader classLoader, ClassHandler classHandler) {
        try {
            Field field = classLoader.loadClass(RobolectricInternals.class.getName()).getDeclaredField("classHandler");
            field.setAccessible(true);
            field.set(null, classHandler);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    @Instrument
    public static class ExampleClass {
        static int foo = 123;

        public String normalMethod(String stringArg, int intArg) {
            return "normalMethod(" + stringArg + ", " + intArg + ")";
        }

        public int normalMethodReturningPrimitive(int intArg) {
            return intArg + 1;
        }

        public native String nativeMethod(String stringArg, int intArg);

        //        abstract void abstractMethod(); todo
    }

    @Test public void shouldInvokeShadowForEachConstructorInInheritanceTree() throws Exception {
        loadClass(Child.class).newInstance();
        transcript.assertEventsSoFar(
                "methodInvoked: Grandparent.__constructor__()",
                "methodInvoked: Parent.__constructor__()",
                "methodInvoked: Child.__constructor__()");
    }

    @Instrument
    public static class Child extends Parent {
    }

    @Instrument
    public static class Parent extends Grandparent {
    }

    @Instrument
    public static class Grandparent {
    }

    @Test public void shouldRetainSuperCallInConstructor() throws Exception {
        Class<?> aClass = loadClass(InstrumentedChild.class);
        Object o = aClass.getDeclaredConstructor(String.class).newInstance("hortense");
        assertEquals("HORTENSE", aClass.getSuperclass().getDeclaredField("parentName").get(o));
        assertNull(aClass.getDeclaredField("childName").get(o));
    }

    @Instrument
    public static class InstrumentedChild extends UninstrumentedParent {
        public final String childName;

        public InstrumentedChild(String name) {
            super(name.toUpperCase());
            this.childName = name;
        }
    }

    @DoNotInstrument
    public static class UninstrumentedParent {
        public final String parentName;

        public UninstrumentedParent(String name) {
            this.parentName = name;
        }
    }

    public static class MyClassHandler implements ClassHandler {
        private static Object GENERATE_YOUR_OWN_VALUE = new Object();
        private Transcript transcript;
        private Object valueToReturn = GENERATE_YOUR_OWN_VALUE;

        public MyClassHandler(Transcript transcript) {
            this.transcript = transcript;
        }

        @Override
        public void reset() {
        }

        @Override
        public void classInitializing(Class clazz) {
        }

        @Override
        public Object methodInvoked(Class clazz, String methodName, Object instance, String[] paramTypes, Object[] params) throws Throwable {
            StringBuilder buf = new StringBuilder();
            buf.append("methodInvoked: ").append(clazz.getSimpleName()).append(".").append(methodName).append("(");
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(paramTypes[i]).append(" ").append(params[i]);
            }
            buf.append(")");
            transcript.add(buf.toString());

            if (valueToReturn != GENERATE_YOUR_OWN_VALUE) return valueToReturn;
            return "response from " + buf.toString();
        }

        @Override
        public Object intercept(Class clazz, String methodName, Object instance, Object[] paramTypes, Object[] params) throws Throwable {
            return null;
        }

        @Override
        public void setStrictI18n(boolean strictI18n) {
        }
    }

    private static class MySetup extends Setup {
        private final boolean shouldAcquire;
        private final boolean shouldInstrument;

        private MySetup(boolean shouldAcquire, boolean shouldInstrument) {
            this.shouldAcquire = shouldAcquire;
            this.shouldInstrument = shouldInstrument;
        }

        @Override
        public boolean shouldAcquire(String name) {
            return shouldAcquire && name.equals(ExampleClass.class.getName());
        }

        @Override
        public boolean shouldInstrument(Class clazz) {
            return shouldInstrument && clazz.equals(ExampleClass.class);
        }
    }

    private Class<?> loadClass(Class<?> clazz) throws ClassNotFoundException {
        return classLoader.loadClass(clazz.getName());
    }
}
