package com.xtremelabs.robolectric;

import android.app.Application;
import android.content.res.Resources;
import com.xtremelabs.robolectric.annotation.DisableStrictI18n;
import com.xtremelabs.robolectric.annotation.EnableStrictI18n;
import com.xtremelabs.robolectric.annotation.Values;
import com.xtremelabs.robolectric.bytecode.ClassHandler;
import com.xtremelabs.robolectric.bytecode.RobolectricClassLoader;
import com.xtremelabs.robolectric.internal.RobolectricTestRunnerInterface;
import com.xtremelabs.robolectric.res.PackageResourceLoader;
import com.xtremelabs.robolectric.res.ResourceLoader;
import com.xtremelabs.robolectric.res.ResourcePath;
import com.xtremelabs.robolectric.shadows.ShadowApplication;
import com.xtremelabs.robolectric.shadows.ShadowLog;
import com.xtremelabs.robolectric.shadows.ShadowResources;
import com.xtremelabs.robolectric.util.DatabaseConfig;
import com.xtremelabs.robolectric.util.DatabaseConfig.DatabaseMap;
import com.xtremelabs.robolectric.util.DatabaseConfig.UsingDatabaseMap;
import com.xtremelabs.robolectric.util.SQLiteMap;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.xtremelabs.robolectric.Robolectric.shadowOf;

/**
 * Installs a {@link RobolectricClassLoader} and {@link com.xtremelabs.robolectric.res.ResourceLoader} in order to
 * provide a simulation of the Android runtime environment.
 */
public class RobolectricTestRunner extends BlockJUnit4ClassRunner implements RobolectricTestRunnerInterface {
    private static Map<AndroidManifest, ResourceLoader> resourceLoaderForRootAndDirectory = new HashMap<AndroidManifest, ResourceLoader>();

    // field in both the instrumented and original classes
    RobolectricContext sharedRobolectricContext;

    // fields in the RobolectricTestRunner in the original ClassLoader
    private RobolectricTestRunnerInterface delegate;
    private final DatabaseMap databaseMap;

    /**
     * Creates a runner to run {@code testClass}. Looks in your working directory for your AndroidManifest.xml file
     * and res directory.
     *
     * @param testClass the test class to be run
     * @throws InitializationError if junit says so
     */
    public RobolectricTestRunner(final Class<?> testClass) throws InitializationError {
        super(RobolectricContext.bootstrap(RobolectricTestRunner.class, testClass, new RobolectricContext.Factory() {
            @Override
            public RobolectricContext create() {
                return new RobolectricContext();
            }
        }));

        sharedRobolectricContext = RobolectricContext.mostRecentRobolectricContext; // ick, race condition

        if (isBootstrapped(getClass())) {
            databaseMap = setupDatabaseMap(testClass, new SQLiteMap());
        } else {
            delegate = sharedRobolectricContext.getBootstrappedTestRunner(this);
            Thread.currentThread().setContextClassLoader(sharedRobolectricContext.getRobolectricClassLoader());
            databaseMap = null;
        }
    }

    public RobolectricContext getRobolectricContext() {
        return sharedRobolectricContext;
    }

    protected static boolean isBootstrapped(Class<?> clazz) {
        return clazz.getClassLoader() instanceof RobolectricClassLoader;
    }

    @Override protected Statement methodBlock(final FrameworkMethod method) {
        sharedRobolectricContext.getClassHandler().reset();
      try {
        delegate.internalBeforeTest(method.getMethod());
      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }

      final Statement statement = super.methodBlock(method);
        return new Statement() {
            @Override public void evaluate() throws Throwable {
            	HashMap<Field,Object> withConstantAnnos = getWithConstantAnnotations(method.getMethod());

            	// todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
                try {
                	if (withConstantAnnos.isEmpty()) {
                		statement.evaluate();
                	}
                	else {
                		synchronized(this) {
	                		setupConstants(withConstantAnnos);
	                		statement.evaluate();
	                		setupConstants(withConstantAnnos);
                		}
                	}
                } finally {
                    delegate.internalAfterTest(method.getMethod());
                }
            }
        };
    }

    /*
     * Called before each test method is run. Sets up the simulation of the Android runtime environment.
     */
    @Override final public void internalBeforeTest(final Method method) {
        setupLogging();
        configureShadows(method);

        Robolectric.resetStaticState();
        resetStaticState();

        DatabaseConfig.setDatabaseMap(databaseMap); //Set static DatabaseMap in DBConfig

        setupApplicationState(method);

        beforeTest(method);
    }

    @Override public void internalAfterTest(final Method method) {
        afterTest(method);
    }

    /**
     * Called before each test method is run.
     *
     * @param method the test method about to be run
     */
    public void beforeTest(final Method method) {
    }

    /**
     * Called after each test method is run.
     *
     * @param method the test method that just ran.
     */
    public void afterTest(final Method method) {
    }

    /**
     * You probably don't want to override this method. Override #prepareTest(Object) instead.
     *
     * @see BlockJUnit4ClassRunner#createTest()
     */
    @Override
    public Object createTest() throws Exception {
        if (delegate != null) {
            return delegate.createTest();
        } else {
            Object test = super.createTest();
            prepareTest(test);
            return test;
        }
    }

    public void prepareTest(final Object test) {
    }

    public void setupApplicationState(Method testMethod) {
        boolean strictI18n = determineI18nStrictState(testMethod);

        ResourceLoader systemResourceLoader = new PackageResourceLoader(sharedRobolectricContext.getSystemResourcePath());
        ShadowResources.setSystemResources(systemResourceLoader);

        ResourceLoader resourceLoader = getResourceLoader(sharedRobolectricContext.getAppManifest());
        resourceLoader.setStrictI18n(strictI18n);

        ClassHandler classHandler = sharedRobolectricContext.getClassHandler();
        classHandler.setStrictI18n(strictI18n);

        Robolectric.application = ShadowApplication.bind(createApplication(), resourceLoader);

        String qualifiers = determineResourceQualifiers(testMethod);
        shadowOf(Resources.getSystem().getConfiguration()).overrideQualifiers(qualifiers);
        shadowOf(Robolectric.application.getResources().getConfiguration()).overrideQualifiers(qualifiers);
    }

    protected void configureShadows(Method testMethod) { // todo: dedupe this/bindShadowClasses
        Robolectric.bindDefaultShadowClasses();
        bindShadowClasses(testMethod);
    }

    /**
     * Override this method to bind your own shadow classes
     */
    @SuppressWarnings("UnusedParameters")
    protected void bindShadowClasses(Method testMethod) {
        bindShadowClasses();
    }

    /**
     * Override this method to bind your own shadow classes
     */
    protected void bindShadowClasses() {
    }

    /**
     * Override this method to reset the state of static members before each test.
     */
    protected void resetStaticState() {
    }

    private String determineResourceQualifiers(Method method) {
        String qualifiers = "";
        Values values = method.getAnnotation(Values.class);
        if (values != null) {
            qualifiers = values.qualifiers();
            if (qualifiers.isEmpty()) {
                qualifiers = values.locale();
            }
        }
        return qualifiers;
    }

    /**
     * Sets Robolectric config to determine if Robolectric should blacklist API calls that are not
     * I18N/L10N-safe.
     * <p/>
     * I18n-strict mode affects suitably annotated shadow methods. Robolectric will throw exceptions
     * if these methods are invoked by application code. Additionally, Robolectric's ResourceLoader
     * will throw exceptions if layout resources use bare string literals instead of string resource IDs.
     * <p/>
     * To enable or disable i18n-strict mode for specific test cases, annotate them with
     * {@link com.xtremelabs.robolectric.annotation.EnableStrictI18n} or
     * {@link com.xtremelabs.robolectric.annotation.DisableStrictI18n}.
     * <p/>
     *
     * By default, I18n-strict mode is disabled.
     *
     * @param method
     *
     */
    private boolean determineI18nStrictState(Method method) {
    	// Global
    	boolean strictI18n = globalI18nStrictEnabled();

    	// Test case class
        Class<?> testClass = method.getDeclaringClass();
        if (testClass.getAnnotation(EnableStrictI18n.class) != null) {
            strictI18n = true;
        } else if (testClass.getAnnotation(DisableStrictI18n.class) != null) {
            strictI18n = false;
        }

    	// Test case method
        if (method.getAnnotation(EnableStrictI18n.class) != null) {
            strictI18n = true;
        } else if (method.getAnnotation(DisableStrictI18n.class) != null) {
            strictI18n = false;
        }

		return strictI18n;
    }

    /**
     * Default implementation of global switch for i18n-strict mode.
     * To enable i18n-strict mode globally, set the system property
     * "robolectric.strictI18n" to true. This can be done via java
     * system properties in either Ant or Maven.
     * <p/>
     * Subclasses can override this method and establish their own policy
     * for enabling i18n-strict mode.
     *
     * @return
     */
    protected boolean globalI18nStrictEnabled() {
    	return Boolean.valueOf(System.getProperty("robolectric.strictI18n"));
    }

    /**
	 * Find all the class and method annotations and pass them to
	 * addConstantFromAnnotation() for evaluation.
	 *
	 * TODO: Add compound annotations to suport defining more than one int and string at a time
	 * TODO: See http://stackoverflow.com/questions/1554112/multiple-annotations-of-the-same-type-on-one-element
	 *
	 * @param method
	 * @return
	 */
    private HashMap<Field,Object> getWithConstantAnnotations(Method method) {
    	HashMap<Field,Object> constants = new HashMap<Field,Object>();

    	for(Annotation anno:method.getDeclaringClass().getAnnotations()) {
    		addConstantFromAnnotation(constants, anno);
    	}

    	for(Annotation anno:method.getAnnotations()) {
    		addConstantFromAnnotation(constants, anno);
    	}

    	return constants;
    }


    /**
     * If the annotation is a constant redefinition, add it to the provided hash
     *
     * @param constants
     * @param anno
     */
    private void addConstantFromAnnotation(HashMap<Field,Object> constants, Annotation anno) {
        try {
        	String name = anno.annotationType().getName();
        	Object newValue = null;
    	
	    	if (name.equals( "com.xtremelabs.robolectric.annotation.WithConstantString" )) {
	    		newValue = (String) anno.annotationType().getMethod("newValue").invoke(anno);
	    	} 
	    	else if (name.equals( "com.xtremelabs.robolectric.annotation.WithConstantInt" )) {
	    		newValue = (Integer) anno.annotationType().getMethod("newValue").invoke(anno);
	    	}
	    	else {
	    		return;
	    	}

    		@SuppressWarnings("rawtypes")
			Class classWithField = (Class) anno.annotationType().getMethod("classWithField").invoke(anno);
    		String fieldName = (String) anno.annotationType().getMethod("fieldName").invoke(anno);
            Field field = classWithField.getDeclaredField(fieldName);
            constants.put(field, newValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Defines static finals from the provided hash and stores the old values back
     * into the hash.
     *
     * Call it twice with the same hash, and it puts everything back the way it was originally.
     *
     * @param constants
     */
    private void setupConstants(HashMap<Field,Object> constants) {
    	for(Field field:constants.keySet()) {
    		Object newValue = constants.get(field);
    		Object oldValue = Robolectric.Reflection.setFinalStaticField(field, newValue);
    		constants.put(field,oldValue);
    	}
    }

    private void setupLogging() {
        String logging = System.getProperty("robolectric.logging");
        if (logging != null && ShadowLog.stream == null) {
            PrintStream stream = null;
            if ("stdout".equalsIgnoreCase(logging)) {
                stream = System.out;
            } else if ("stderr".equalsIgnoreCase(logging)) {
                stream = System.err;
            } else {
                try {
                    final PrintStream file = new PrintStream(new FileOutputStream(logging));
                    stream = file;
                    Runtime.getRuntime().addShutdownHook(new Thread() {
                        @Override public void run() {
                            try { file.close(); } catch (Exception ignored) { }
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            ShadowLog.stream = stream;
        }
    }

    /**
     * Override this method if you want to provide your own implementation of Application.
     * <p/>
     * This method attempts to instantiate an application instance as specified by the AndroidManifest.xml.
     *
     * @return An instance of the Application class specified by the ApplicationManifest.xml or an instance of
     *         Application if not specified.
     */
    protected Application createApplication() {
        return new ApplicationResolver(sharedRobolectricContext.getAppManifest()).resolveApplication();
    }

    private ResourceLoader getResourceLoader(final AndroidManifest androidManifest) {
        ResourceLoader resourceLoader = resourceLoaderForRootAndDirectory.get(androidManifest);
        if (resourceLoader == null ) {
            List<ResourcePath> resourcePaths = sharedRobolectricContext.getResourcePaths();
            resourceLoader = createResourceLoader(resourcePaths);
            resourceLoaderForRootAndDirectory.put(androidManifest, resourceLoader);
        }
        return resourceLoader;
    }

    // this method must live on a RobolectricClassLoader-loaded class, so it can't be on RobolectricContext
    protected ResourceLoader createResourceLoader(List<ResourcePath> resourcePaths) {
        return new PackageResourceLoader(resourcePaths);
    }

    private String findResourcePackageName(final File projectManifestFile) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(projectManifestFile);

        String projectPackage = doc.getElementsByTagName("manifest").item(0).getAttributes().getNamedItem("package").getTextContent();

        return projectPackage + ".R";
    }

    /*
     * Specifies what database to use for testing (ex: H2 or Sqlite),
     * this will load H2 by default, the SQLite TestRunner version will override this.
     */
    protected DatabaseMap setupDatabaseMap(Class<?> testClass, DatabaseMap map) {
    	DatabaseMap dbMap = map;

    	if (testClass.isAnnotationPresent(UsingDatabaseMap.class)) {
	    	UsingDatabaseMap usingMap = testClass.getAnnotation(UsingDatabaseMap.class);
	    	if(usingMap.value()!=null){
	    		dbMap = Robolectric.newInstanceOf(usingMap.value());
	    	} else {
	    		if (dbMap==null)
		    		throw new RuntimeException("UsingDatabaseMap annotation value must provide a class implementing DatabaseMap");
	    	}
    	}
    	return dbMap;
    }
}
