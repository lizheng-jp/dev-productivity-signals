package dev.productivity.signals.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonObjectConverter {

    private JsonObjectConverter() {
    }

    public static Map<String, Object> toMap(JSONObject json) {
        Map<String, Object> result = new LinkedHashMap<>();
        json.keys().forEachRemaining(key -> result.put(key, toJavaValue(json.get(key))));
        return result;
    }

    private static Object toJavaValue(Object value) {
        if (value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject object) {
            return toMap(object);
        }
        if (value instanceof JSONArray array) {
            List<Object> result = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                result.add(toJavaValue(array.get(index)));
            }
            return result;
        }
        return value;
    }
}
