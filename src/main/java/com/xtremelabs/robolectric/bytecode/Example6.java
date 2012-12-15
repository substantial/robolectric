package com.xtremelabs.robolectric.bytecode;

public class Example6 {
    private Object __robo_data__;

    public String someMethod(String arg1, int arg2) throws Exception {
        if ((__robo_data__ instanceof Example6) || RobolectricInternals.shouldCallDirectly(this)) {
            return ((Example6) __robo_data__).$$robo$$Example$2345$someMethod(arg1, arg2);
        } else {
            try {
                return (String) RobolectricInternals.methodInvoked(Example6.class, "<methodName>", this,
                        new String[]{"java.lang.String", "int"},
                        new Object[]{arg1, arg2});
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        }
    }

    public void println() {
        System.out.println(RobolectricInternals.class);
    }

    private String $$robo$$Example$2345$someMethod(String arg1, int arg2) {
        return null;
    }
}
