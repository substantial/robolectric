package com.xtremelabs.robolectric.bytecode;

import com.xtremelabs.robolectric.internal.Instrument;
import com.xtremelabs.robolectric.util.Transcript;
import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.Loader;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class ClassTransformerTest {

    private Loader loader;
    private Transcript transcript = new Transcript();
    private Class<?> exampleClass;

    @Before
    public void setUp() throws Exception {
        ClassPool cp = new ClassPool();
        cp.appendClassPath(new ClassClassPath(getClass()));
        loader = new Loader();
        loader.delegateLoadingOf(ClassHandler.class.getName());
        loader.addTranslator(cp, new AndroidTranslator(new ZipClassCache("tmp/zzz.jar", -1), new Setup()));
        injectClassHandler(loader, new MyClassHandler(transcript));

        exampleClass = loader.loadClass(ExampleClass.class.getName());
    }

    @Test
    public void callingNormalMethodShouldInvokeClassHandler() throws Exception {
        Method normalMethod = exampleClass.getMethod("normalMethod", String.class, int.class);
//        assertEquals(Modifiers.PUBLIC, normalMethod.getModifiers());

//        RobolectricInternals.directlyOn(shadowedObject)
        Object exampleInstance = exampleClass.newInstance();
        assertEquals("response from methodInvoked: ExampleClass.normalMethod(java.lang.String value1, int 123)",
                normalMethod.invoke(exampleInstance, "value1", 123));
        transcript.assertEventsSoFar("methodInvoked: ExampleClass.__constructor__()",
                "methodInvoked: ExampleClass.normalMethod(java.lang.String value1, int 123)");
    }

    @Test public void shouldGenerateClassSpecificDirectAccessMethod() throws Exception {
        Method directMethod = exampleClass.getMethod("$$robo$$ClassTransformerTest$ExampleClass_d3b0_normalMethod", String.class, int.class);

        Object exampleInstance = exampleClass.newInstance();
        assertEquals("normalMethod(value1, 123)", directMethod.invoke(exampleInstance, "value1", 123));
        transcript.assertEventsSoFar("methodInvoked: ExampleClass.__constructor__()");
    }

    @SuppressWarnings("UnusedDeclaration")
    @Instrument
    public static class ExampleClass {
        public String normalMethod(String stringArg, int intArg) {
            return "normalMethod(" + stringArg + ", " + intArg + ")";
        }

        private native void privateNativeMethod();
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
}
