package com.rey.modelquery.tck.vnd.ins;

import java.io.IOException;
import java.io.InputStream;
import org.hibernate.bytecode.enhance.spi.DefaultEnhancementContext;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.internal.BytecodeProviderInitiator;

/**
 * Loads one entity class bytecode-enhanced by Hibernate's own enhancer, as the build plugin would, and every other
 * class from its parent; the D-116 {@code persist} probe and the D-119 entity-mode update map the enhanced copy.
 */
public final class EnhancingClassLoader extends ClassLoader {

    private final String enhancedName;
    private final Enhancer enhancer =
            BytecodeProviderInitiator.buildDefaultBytecodeProvider().getEnhancer(new DefaultEnhancementContext());

    public EnhancingClassLoader(Class<?> entity) {
        super(entity.getClassLoader());
        this.enhancedName = entity.getName();
    }

    /** The enhanced copy of the entity this loader was built for. */
    public Class<?> enhanced() {
        try {
            return loadClass(enhancedName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.equals(enhancedName)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] enhanced = enhancer.enhance(name, original(name));
                if (enhanced == null) {
                    throw new IllegalStateException("Hibernate's enhancer left " + name + " unchanged");
                }
                loaded = defineClass(name, enhanced, 0, enhanced.length);
            }
            return loaded;
        }
    }

    private byte[] original(String name) throws ClassNotFoundException {
        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
