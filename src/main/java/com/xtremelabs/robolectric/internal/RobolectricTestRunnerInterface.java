package com.xtremelabs.robolectric.internal;

import com.xtremelabs.robolectric.RobolectricContext;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

import java.lang.reflect.Method;

public interface RobolectricTestRunnerInterface {
    Object createTest() throws Exception;

    void internalBeforeTest(Method method);

    void internalAfterTest(Method method);

    TestClass getTestClass();

    void setRobolectricContext(RobolectricContext robolectricContext);

    Statement methodBlock(FrameworkMethod method);
}
