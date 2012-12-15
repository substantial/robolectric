package com.xtremelabs.robolectric.bytecode;

import com.xtremelabs.robolectric.internal.Instrument;
import com.xtremelabs.robolectric.util.Transcript;
import javassist.*;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class InstrumentingClassLoaderTest {

    private Transcript transcript = new Transcript();
    private Class<?> exampleClass;
    private MyClassHandler classHandler;

    @Before
    public void setUp() throws Exception {
//        transformWithJavassist();
        ClassLoader classLoader = transformWithAsm();
        classHandler = new MyClassHandler(transcript);
        injectClassHandler(classLoader, classHandler);
        exampleClass = classLoader.loadClass(ExampleClass.class.getName());
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
//        assertEquals(Modifiers.PUBLIC, normalMethod.getModifiers());

//        RobolectricInternals.directlyOn(shadowedObject)
        Object exampleInstance = exampleClass.newInstance();
        assertEquals("response from methodInvoked: ExampleClass.normalMethod(java.lang.String value1, int 123)",
                normalMethod.invoke(exampleInstance, "value1", 123));
        transcript.assertEventsSoFar(/*"methodInvoked: ExampleClass.__constructor__()",*/
                "methodInvoked: ExampleClass.normalMethod(java.lang.String value1, int 123)");
    }

    @Test
    public void callingNormalMethodReturningPrimitiveShouldInvokeClassHandler() throws Exception {
        classHandler.valueToReturn = 456;

        Method normalMethod = exampleClass.getMethod("normalMethodReturningPrimitive", int.class);
//        assertEquals(Modifiers.PUBLIC, normalMethod.getModifiers());

//        RobolectricInternals.directlyOn(shadowedObject)
        Object exampleInstance = exampleClass.newInstance();
        assertEquals(456,
                normalMethod.invoke(exampleInstance, 123));
        transcript.assertEventsSoFar(/*"methodInvoked: ExampleClass.__constructor__()",*/
                "methodInvoked: ExampleClass.normalMethodReturningPrimitive(int 123)");
    }

    @Test public void shouldGenerateClassSpecificDirectAccessMethod() throws Exception {
        Method directMethod = exampleClass.getMethod("$$robo$$InstrumentingClassLoaderTest$ExampleClass_d501_normalMethod", String.class, int.class);

        Object exampleInstance = exampleClass.newInstance();
        assertEquals("normalMethod(value1, 123)", directMethod.invoke(exampleInstance, "value1", 123));
        transcript.assertEventsSoFar(/*"methodInvoked: ExampleClass.__constructor__()"*/);
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

    public static class MyClassHandler implements ClassHandler {
        private Transcript transcript;
        private Object valueToReturn;

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
                buf.append(paramTypes[i]).append(" ").append(params[i].toString());
            }
            buf.append(")");
            transcript.add(buf.toString());

            if (valueToReturn != null) return valueToReturn;
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

    @SuppressWarnings("UnusedDeclaration")
    @Instrument
    public static class ExampleClass {
        public String normalMethod(String stringArg, int intArg) {
            return "normalMethod(" + stringArg + ", " + intArg + ")";
        }

        public int normalMethodReturningPrimitive(int intArg) {
            return intArg + 1;
        }

        private native void privateNativeMethod();

    //        abstract void abstractMethod(); todo
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
}
