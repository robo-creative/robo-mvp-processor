/**
 * Copyright (c) 2016 Robo Creative - https://robo-creative.github.io.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package com.robo.mvp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

/**
 * Pre-processes annotations at compile-time and generates indexed presenter
 * mapping file to speed up Robo MVP applications.
 * 
 * @author robo-admin
 *
 */
@SupportedAnnotationTypes("com.robo.mvp.BindTo")
@SupportedOptions("presenterMappingCollector")
public class AnnotationProcessor extends AbstractProcessor {

	private Map<String, BindTo> mBindings;
	private String mPresenterMap;

	private static final String OPTION_PRESENTER_MAPPING_COLLECTOR = "presenterMappingCollector";

	public AnnotationProcessor() {
		super();
		mBindings = new HashMap<>();
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		try {
			mPresenterMap = processingEnv.getOptions().get(OPTION_PRESENTER_MAPPING_COLLECTOR);
			if (null == mPresenterMap) {
				printError("No presenter map file specified.");
				return false;
			}
			if (roundEnv.processingOver() && !annotations.isEmpty()) {
				printError("Annotation processing has done but annotations still available.");
				return false;
			}
			if (annotations.isEmpty()) {
				return false;
			}
			collectPresenterMappings(roundEnv);
			createIdentityMapFile(mPresenterMap);
			return true;
		} catch (RuntimeException ex) {
			ex.printStackTrace();
			printError("Failed to process annotations due to unhandled exception: " + ex);
			return false;
		}
	}

	private void collectPresenterMappings(RoundEnvironment roundEnv) {
		Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(BindTo.class);
		for (Element element : elements) {
			BindTo mapping = element.getAnnotation(BindTo.class);
			String enclosingType = ((TypeElement) element).getQualifiedName().toString();
			printInfo("Found presenter for " + enclosingType);
			mBindings.put(enclosingType, mapping);
		}
	}

	private void createIdentityMapFile(String file) {
		try {
			JavaFileObject sourceFile = processingEnv.getFiler().createSourceFile(file);
			int targetPackageEndIndex = file.lastIndexOf('.');
			String targetPackage = targetPackageEndIndex > 0 ? file.substring(0, targetPackageEndIndex) : null;
			String clazz = file.substring(targetPackageEndIndex + 1);
			try (BufferedWriter writer = new BufferedWriter(sourceFile.openWriter())) {
				if (targetPackage != null) {
					writer.write("package " + targetPackage + ";\n\n");
				}
				writer.write("import java.util.HashMap;\n");
				writer.write("import java.util.Map;\n\n");
                writer.write("import java.util.Collection;\n\n");

				writer.write("import com.robo.mvp.View;\n");
				writer.write("import com.robo.mvp.Presenter;\n");
				writer.write("import com.robo.mvp.PresenterMapping;\n");
				writer.write("import com.robo.mvp.PresenterMappingCollector;\n\n");
                writer.write("import com.robo.reflect.TypeUtils;\n");

				writer.write("public class " + clazz + " implements PresenterMappingCollector {\n");
				writer.write("    static final Map<Class<? extends View>, PresenterMapping> PRESENTER_MAPPINGS;\n\n");
				writer.write("    static {\n");
				writer.write("        PRESENTER_MAPPINGS = new HashMap<>();\n\n");
				writeMappingEntries(writer);
				writer.write("    }\n\n");

				writer.write("    @Override\n");
				writer.write("    public PresenterMapping collectPresenterMapping(View view) {\n");
				writer.write("        Class<? extends View> viewType = view.getClass();\n");
                writer.write("        if (PRESENTER_MAPPINGS.containsKey(viewType)) {\n");
                writer.write("            return PRESENTER_MAPPINGS.get(viewType);\n");
                writer.write("        }\n");
                writer.write("        Collection<Class<?>> superTypesIncludingInterfaces = TypeUtils.fetchAllSuperTypes(viewType, null);\n");
                writer.write("        superTypesIncludingInterfaces = TypeUtils.fetchAllInterfaces(viewType, superTypesIncludingInterfaces);\n");
                writer.write("        for (Class<?> baseType : superTypesIncludingInterfaces) {\n");
                writer.write("            if (PRESENTER_MAPPINGS.containsKey(baseType)) {\n");
                writer.write("                return PRESENTER_MAPPINGS.get(baseType);\n");
                writer.write("            }\n");
                writer.write("        }\n");
				writer.write("        return null;\n");
		    	writer.write("    }\n\n");
				writer.write(
						"    private static void addInternal(Class<? extends View> identity, Class<? extends Presenter> presenterType) {\n");
				writer.write("        addInternal(identity, new PresenterMapping(presenterType, identity));\n");
				writer.write("    }\n\n");

				writer.write(
						"    private static void addInternal(Class<? extends View> identity, PresenterMapping mapping) {\n");
				writer.write("        PRESENTER_MAPPINGS.put(identity, mapping);\n");
				writer.write("    }\n");
				writer.write("}\n");
				
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to create identity map file: " + file, e);
		}
	}

	private void writeMappingEntries(BufferedWriter writer) throws IOException {
		String presenterType;
		for (String identity : mBindings.keySet()) {
			try {
				presenterType = mBindings.get(identity).value().getName();
			} catch (MirroredTypeException ex) {
				presenterType = ex.getTypeMirror().toString();
			}
			writer.write("        addInternal(" + identity + ".class, " + presenterType + ".class);\n");
		}
	}

	private void printError(String message) {
		processingEnv.getMessager().printMessage(Kind.ERROR, message);
	}

	private void printInfo(String message) {
		processingEnv.getMessager().printMessage(Kind.NOTE, message);
	}
}
