package com.xtremelabs.robolectric.shadows;

import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Display;
import com.xtremelabs.robolectric.Robolectric;
import com.xtremelabs.robolectric.internal.Implementation;
import com.xtremelabs.robolectric.internal.Implements;
import com.xtremelabs.robolectric.internal.RealObject;
import com.xtremelabs.robolectric.res.ResourceExtractor;
import com.xtremelabs.robolectric.res.ResourceLoader;
import com.xtremelabs.robolectric.tester.android.util.TestAttributeSet;
import java.io.InputStream;
import java.util.Locale;

import static com.xtremelabs.robolectric.Robolectric.newInstanceOf;
import static com.xtremelabs.robolectric.Robolectric.shadowOf;

/**
 * Shadow of {@code Resources} that simulates the loading of resources
 *
 * @see com.xtremelabs.robolectric.RobolectricTestRunner#RobolectricTestRunner(Class)
 */
@SuppressWarnings({"UnusedDeclaration"})
@Implements(Resources.class)
public class ShadowResources {
    private float density = 1.0f;
    Configuration configuration = null;
    private DisplayMetrics displayMetrics;
    private Display display;

    private static Resources system = null;

    public static void setSystemResources(ResourceLoader systemResourceLoader) {
        system = ShadowResources.bind(new Resources(null, null, null), systemResourceLoader);
    }

    static Resources bind(Resources resources, ResourceLoader resourceLoader) {
        ShadowResources shadowResources = shadowOf(resources);
        if (shadowResources.resourceLoader != null) throw new RuntimeException("ResourceLoader already set!");
        shadowResources.resourceLoader = resourceLoader;
        return resources;
    }

    @RealObject Resources realResources;
    private ResourceLoader resourceLoader;

    public ShadowResources() {
        Configuration configuration = new Configuration();
        configuration.setToDefaults();
        setConfiguration(configuration);
    }

    @Implementation
    public void __constructor__(AssetManager assets, DisplayMetrics metrics, Configuration config) {
        this.displayMetrics = metrics;
        setConfiguration(config);
    }

    /**
     * Non-Android accessor that sets the value to be returned by {@link #getConfiguration()}
     *
     * @param configuration Configuration instance to set on this Resources obj
     */
    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    @Implementation
    public int getIdentifier(String name, String defType, String defPackage) {
        ResourceExtractor resourceExtractor = resourceLoader.getResourceExtractor();

        Integer index = resourceExtractor.getResourceId(defType + "/" + name, defPackage);
        if (index == null) {
            return 0;
        }
        return index;
    }

    @Implementation
    public int getColor(int id) throws Resources.NotFoundException {
        return resourceLoader.getColorValue(id, getQualifiers());
    }

    private String getQualifiers() {
        return shadowOf(getConfiguration()).getQualifiers();
    }

    @Implementation
    public ColorStateList getColorStateList(int id) {
        return new ColorStateList(null, null);
    }

    @Implementation
    public Configuration getConfiguration() {
        if (configuration == null) {
            configuration = new Configuration();
            configuration.setToDefaults();
        }
        if (configuration.locale == null) {
            configuration.locale = Locale.getDefault();
        }
        return configuration;
    }

    @Implementation
    public String getString(int id) throws Resources.NotFoundException {
        return resourceLoader.getStringValue(id, getQualifiers());
    }

    @Implementation
    public String getString(int id, Object... formatArgs) throws Resources.NotFoundException {
        String raw = getString(id);
        return String.format(Locale.ENGLISH, raw, formatArgs);
    }

    @Implementation
    public String getQuantityString(int id, int quantity, Object... formatArgs) throws Resources.NotFoundException {
        String raw = getQuantityString(id, quantity);
        return String.format(Locale.ENGLISH, raw, formatArgs);
    }

    @Implementation
    public String getQuantityString(int id, int quantity) throws Resources.NotFoundException {
        return resourceLoader.getPluralStringValue(id, quantity, getQualifiers());
    }

    @Implementation
    public InputStream openRawResource(int id) throws Resources.NotFoundException {
        return resourceLoader.getRawValue(id);
    }

    @Implementation
    public String[] getStringArray(int id) throws Resources.NotFoundException {
        String[] arrayValue = resourceLoader.getStringArrayValue(id, getQualifiers());
        if (arrayValue == null) {
            throw new Resources.NotFoundException();
        }
        return arrayValue;
    }

    @Implementation
    public CharSequence[] getTextArray(int id) throws Resources.NotFoundException {
        return getStringArray(id);
    }

    @Implementation
    public CharSequence getText(int id) throws Resources.NotFoundException {
        return getString(id);
    }
    
    public void setDensity(float density) {
        this.density = density;
    }

    public void setDisplay(Display display) {
        this.display = display;
        displayMetrics = null;
    }

    @Implementation
    public DisplayMetrics getDisplayMetrics() {
        if (displayMetrics == null) {
            if (display == null) {
                display = Robolectric.newInstanceOf(Display.class);
            }

            displayMetrics = new DisplayMetrics();
            display.getMetrics(displayMetrics);
        }
        displayMetrics.density = this.density;
        return displayMetrics;
    }

    @Implementation
    public Drawable getDrawable(int drawableResourceId) throws Resources.NotFoundException {
        return resourceLoader.getDrawable(drawableResourceId, realResources);
    }

    @Implementation
    public float getDimension(int id) throws Resources.NotFoundException {
        return resourceLoader.getDimenValue(id, getQualifiers());
    }

    @Implementation
    public int getInteger(int id) throws Resources.NotFoundException {
    	return resourceLoader.getIntegerValue(id, getQualifiers());
    }

    @Implementation
    public int[] getIntArray(int id) throws Resources.NotFoundException {
        int[] arrayValue = resourceLoader.getIntegerArrayValue(id, getQualifiers());
        if (arrayValue == null) {
            throw new Resources.NotFoundException();
        }
        return arrayValue;
    }

    @Implementation
    public boolean getBoolean(int id) throws Resources.NotFoundException {
    	return resourceLoader.getBooleanValue(id, getQualifiers());
    }
    
    @Implementation
    public int getDimensionPixelSize(int id) throws Resources.NotFoundException {
        return (int) getDimension(id);
    }

    @Implementation
    public int getDimensionPixelOffset(int id) throws Resources.NotFoundException {
        return (int) getDimension(id);
    }

    @Implementation
    public AssetManager getAssets() {
        return ShadowAssetManager.bind(Robolectric.newInstanceOf(AssetManager.class), resourceLoader);
    }
    
    @Implementation
    public XmlResourceParser getXml(int id)
    		throws Resources.NotFoundException {
    	XmlResourceParser parser = resourceLoader.getXml(id);
    	if (parser == null) {
    		throw new Resources.NotFoundException();
    	}
    	return parser;
    }

    @Implementation
    public final android.content.res.Resources.Theme newTheme() {
        return inject(realResources, newInstanceOf(Resources.Theme.class));
    }

    public ResourceLoader getResourceLoader() {
        return resourceLoader;
    }

    @Implements(Resources.Theme.class)
    public static class ShadowTheme implements UsesResources {
        protected Resources resources;

        public void injectResources(Resources resources) {
            this.resources = resources;
        }

        @Implementation
        public TypedArray obtainStyledAttributes(int[] attrs) {
            return obtainStyledAttributes(0, attrs);
        }

        @Implementation
        public TypedArray obtainStyledAttributes(int resid, int[] attrs) throws android.content.res.Resources.NotFoundException {
            return obtainStyledAttributes(null, attrs, 0, 0);
        }

        @Implementation
        public TypedArray obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int defStyleRes) {
            if (set == null) {
                set = new TestAttributeSet();
            }

            return ShadowTypedArray.create(resources, set, attrs);
        }
    }

    @Implementation
    public static Resources getSystem() {
        return system;
    }

    public static <T> T inject(Resources resources, T instance) {
        Object shadow = Robolectric.shadowOf_(instance);
        if (shadow instanceof UsesResources) {
            ((UsesResources) shadow).injectResources(resources);
        }
        return instance;
    }
}
