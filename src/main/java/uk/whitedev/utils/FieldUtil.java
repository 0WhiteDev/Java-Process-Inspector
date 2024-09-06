package uk.whitedev.utils;

import java.lang.reflect.Field;
import java.util.*;

public class FieldUtil {
    private final ClassUtil classUtil = new ClassUtil();
    private final String[] blacklist = new String[]{"java.", "javax.", "sun."};

    public String getAllFields(){
        StringBuilder builder = new StringBuilder("All Process Fields:\n");
        List<String> classes = classUtil.getLoadedClasses();
        for (String aClass : classes) {
            try {
                Class<?> startClass = Class.forName(aClass);
                Map<String, String> fieldMap = new HashMap<>();
                Set<String> processedFields = new HashSet<>();

                inspectClass(startClass, fieldMap, processedFields);

                for (Map.Entry<String, String> entry : fieldMap.entrySet()) {
                    if(checkClassName(entry.getKey()))
                        builder.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            } catch (Exception ignored) {}
        }
        return builder.toString();
    }

    private boolean checkClassName(String text){
        for (String s : blacklist) {
            if(text.startsWith(s)) return false;
        }
        return true;
    }

    private void inspectClass(Class<?> clazz, Map<String, String> fieldMap, Set<String> processedFields) throws IllegalAccessException {
        while (clazz != null) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                String fieldName = field.getName();
                String fieldType = field.getType().getSimpleName();
                Object fieldValue = getFieldValue(field, clazz);
                String fieldKey = clazz.getName() + " " + fieldType + " " + fieldName;

                if (fieldValue != null && processedFields.add(fieldKey)) {
                    fieldMap.put(fieldKey, fieldValue.toString());
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private Object getFieldValue(Field field, Class<?> clazz) throws IllegalAccessException {
        Object instance = null;
        try {
            instance = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception ignored) {}
        if (instance != null) {
            return field.get(instance);
        }
        return null;
    }
}
