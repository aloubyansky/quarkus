package io.quarkus.annotation.processor.extension;

import static javax.lang.model.util.ElementFilter.typesIn;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import io.quarkus.annotation.processor.util.Utils;

class PersistentBuildItemProcessor {

    static void process(Utils utils, RoundEnvironment roundEnv, TypeElement annotation) {
        final Set<TypeElement> typeElements = typesIn(roundEnv.getElementsAnnotatedWith(annotation));
        if (typeElements.isEmpty()) {
            return;
        }

        for (TypeElement persistentBuildItem : typeElements) {
            String binaryName = utils.element().getBinaryName(persistentBuildItem);

            // Collect fields (non-static, non-transient)
            List<FieldInfo> fields = new ArrayList<>();
            for (Element member : utils.processingEnv().getElementUtils().getAllMembers(persistentBuildItem)) {
                if (member.getKind() == ElementKind.FIELD) {
                    VariableElement field = (VariableElement) member;
                    if (!field.getModifiers().contains(Modifier.STATIC) &&
                            !field.getModifiers().contains(Modifier.TRANSIENT)) {
                        String fieldName = field.getSimpleName().toString();
                        String fieldType = field.asType().toString();
                        String getterName = findGetterName(utils, persistentBuildItem, fieldName, fieldType);
                        if (getterName != null) {
                            fields.add(new FieldInfo(fieldName, fieldType, getterName));
                        }
                    }
                }
            }

            // Find constructor matching fields
            ExecutableElement matchingConstructor = findMatchingConstructor(utils, persistentBuildItem, fields);
            if (matchingConstructor == null) {
                utils.processingEnv().getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "No constructor found matching fields for " + binaryName);
                continue;
            }

            // Generate serializer class
            final String simpleName = binaryName.substring(binaryName.lastIndexOf('.') + 1);
            final String serializerName = simpleName + "Serializer";
            try {
                JavaFileObject javaFile = utils.processingEnv().getFiler().createSourceFile(binaryName + "Serializer");
                try (BufferedWriter writer = new BufferedWriter(javaFile.openWriter())) {
                    generateSerializerClass(writer, binaryName, simpleName, serializerName, fields);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static String findGetterName(Utils utils, TypeElement typeElement, String fieldName, String fieldType) {
        String capitalizedFieldName = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String getterName = "get" + capitalizedFieldName;
        String booleanGetterName = "is" + capitalizedFieldName;

        for (Element member : utils.processingEnv().getElementUtils().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) member;
                String methodName = method.getSimpleName().toString();
                if ((methodName.equals(getterName) || methodName.equals(booleanGetterName)) &&
                        method.getParameters().isEmpty() &&
                        method.getReturnType().toString().equals(fieldType)) {
                    return methodName;
                }
            }
        }
        return null;
    }

    private static ExecutableElement findMatchingConstructor(Utils utils, TypeElement typeElement, List<FieldInfo> fields) {
        for (Element member : utils.processingEnv().getElementUtils().getAllMembers(typeElement)) {
            if (member.getKind() == ElementKind.CONSTRUCTOR) {
                ExecutableElement constructor = (ExecutableElement) member;
                List<? extends VariableElement> params = constructor.getParameters();
                if (params.size() == fields.size()) {
                    boolean matches = true;
                    for (int i = 0; i < params.size(); i++) {
                        VariableElement param = params.get(i);
                        FieldInfo field = fields.get(i);
                        if (!param.getSimpleName().toString().equals(field.name) ||
                                !param.asType().toString().equals(field.type)) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) {
                        return constructor;
                    }
                }
            }
        }
        return null;
    }

    private static void generateSerializerClass(BufferedWriter writer, String binaryName, String simpleName,
            String serializerName, List<FieldInfo> fields) throws IOException {
        String packageName = binaryName.substring(0, binaryName.lastIndexOf('.'));

        writer.write("""
                package %s;

                public class %s {

                """.formatted(packageName, serializerName));

        // Generate field name constants
        generateFieldConstants(writer, fields);
        writer.newLine();

        // Generate serialize method
        generateSerializeMethod(writer, simpleName, fields);
        writer.newLine();

        // Generate deserialize method
        generateDeserializeMethod(writer, simpleName, fields);

        writer.write("""
                }
                """);
    }

    private static void generateFieldConstants(BufferedWriter writer, List<FieldInfo> fields) throws IOException {
        for (FieldInfo field : fields) {
            writer.write("    private static final String %s = \"%s\";\n"
                    .formatted(getFieldConstantName(field.name), field.name));
        }
    }

    private static String getFieldConstantName(String fieldName) {
        // Convert camelCase to UPPER_SNAKE_CASE
        StringBuilder result = new StringBuilder("FIELD_");
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(c);
            } else {
                result.append(Character.toUpperCase(c));
            }
        }
        return result.toString();
    }

    private static void generateSerializeMethod(BufferedWriter writer, String simpleName, List<FieldInfo> fields)
            throws IOException {
        writer.write("""
                    public static String serialize(%s item) throws java.io.IOException {
                        io.quarkus.bootstrap.json.Json.JsonObjectBuilder builder = io.quarkus.bootstrap.json.Json.object();
                """.formatted(simpleName));

        for (FieldInfo field : fields) {
            writer.write("        builder.put(%s, %s);\n"
                    .formatted(getFieldConstantName(field.name),
                            convertToJson(field, "item." + field.getterName + "()")));
        }

        writer.write("""
                        StringBuilder sb = new StringBuilder();
                        builder.appendTo(sb);
                        return sb.toString();
                    }
                """);
    }

    private static void generateDeserializeMethod(BufferedWriter writer, String simpleName, List<FieldInfo> fields)
            throws IOException {
        writer.write("""
                    public static %s deserialize(String json) {
                        io.quarkus.bootstrap.json.JsonObject obj = io.quarkus.bootstrap.json.JsonReader.of(json).read();
                """.formatted(simpleName));

        for (FieldInfo field : fields) {
            writer.write("        %s %s = %s;\n"
                    .formatted(field.type, field.name,
                            convertFromJson(field, "obj.get(" + getFieldConstantName(field.name) + ")")));
        }

        StringBuilder constructorParams = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                constructorParams.append(", ");
            }
            constructorParams.append(fields.get(i).name);
        }

        writer.write("""
                        return new %s(%s);
                    }
                """.formatted(simpleName, constructorParams));
    }

    private static String convertToJson(FieldInfo field, String valueExpr) {
        String type = field.type;
        if (type.equals("java.nio.file.Path")) {
            return valueExpr + ".toString()";
        } else if (type.equals("java.lang.String") || type.equals("int") || type.equals("long") ||
                type.equals("boolean") || type.equals("double") || type.equals("float") ||
                type.equals("short") || type.equals("byte")) {
            return valueExpr;
        }
        return valueExpr;
    }

    private static String convertFromJson(FieldInfo field, String jsonExpr) {
        String type = field.type;
        if (type.equals("java.nio.file.Path")) {
            return "java.nio.file.Paths.get(((io.quarkus.bootstrap.json.JsonString)" + jsonExpr + ").value())";
        } else if (type.equals("java.lang.String")) {
            return "((io.quarkus.bootstrap.json.JsonString)" + jsonExpr + ").value()";
        } else if (type.equals("int")) {
            return "(int)((io.quarkus.bootstrap.json.JsonInteger)" + jsonExpr + ").longValue()";
        } else if (type.equals("long")) {
            return "((io.quarkus.bootstrap.json.JsonInteger)" + jsonExpr + ").longValue()";
        } else if (type.equals("boolean")) {
            return "((io.quarkus.bootstrap.json.JsonBoolean)" + jsonExpr + ").value()";
        } else if (type.equals("double")) {
            return "((io.quarkus.bootstrap.json.JsonNumber)" + jsonExpr + ").doubleValue()";
        } else if (type.equals("float")) {
            return "(float)((io.quarkus.bootstrap.json.JsonNumber)" + jsonExpr + ").doubleValue()";
        } else if (type.equals("short")) {
            return "(short)((io.quarkus.bootstrap.json.JsonInteger)" + jsonExpr + ").longValue()";
        } else if (type.equals("byte")) {
            return "(byte)((io.quarkus.bootstrap.json.JsonInteger)" + jsonExpr + ").longValue()";
        }
        return "null";
    }

    private static class FieldInfo {
        final String name;
        final String type;
        final String getterName;

        FieldInfo(String name, String type, String getterName) {
            this.name = name;
            this.type = type;
            this.getterName = getterName;
        }
    }
}
