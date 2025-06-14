package net.fabricmc.loom.api.remapping;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.objectweb.asm.ClassVisitor;

/**
 * A remapper extension can be used to add extra processing to the remapping process.
 *
 * <p>Implementations of RemapperExtension's must have the following:
 * A single constructor annotated with {@link Inject}, and taking a single argument of the parameters.
 * Or a single constructor annotated with {@link Inject} taking no arguments, when the extension does not have any parameters.
 *
 * <p>Use {@link net.fabricmc.loom.api.LoomGradleExtensionAPI#addRemapperExtension(Class, Class, Action)} to register a remapper extension.
 *
 * @param <T> Parameter type for the extension. Should be {@link RemapperParameters.None} if the action does not have parameters.
 */
public interface RemapperExtension<T extends RemapperParameters> {
	/**
	 * Return a {@link ClassVisitor} that will be used when remapping the given class.
	 *
	 * @param className The name of the class being remapped
	 * @param remapperContext The remapper context
	 * @param classVisitor The parent class visitor
	 * @return A {@link ClassVisitor} that will be used when remapping the given class, or the given {@code classVisitor} if no extra processing is required for this class.
	 */
	ClassVisitor insertVisitor(String className, RemapperContext remapperContext, ClassVisitor classVisitor);
}