package com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolErrorKind;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** protobuf Struct 与内部 Map 的唯一映射器，限制嵌套深度并拒绝未知 Java 类型。 */
final class ProtoStructMapper {
    private static final int MAX_DEPTH = 16;

    Map<String, Object> fromProto(Struct struct) {
        return fromStruct(struct, 0);
    }

    Struct toProto(Map<String, Object> data) {
        return toStruct(data, 0);
    }

    private Map<String, Object> fromStruct(Struct struct, int depth) {
        requireDepth(depth);
        Map<String, Object> result = new LinkedHashMap<>();
        struct.getFieldsMap().forEach((key, value) -> result.put(key, fromValue(value, depth + 1)));
        return result;
    }

    private Object fromValue(Value value, int depth) {
        requireDepth(depth);
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> fromStruct(value.getStructValue(), depth + 1);
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(item -> fromValue(item, depth + 1)).toList();
        };
    }

    private Struct toStruct(Map<String, Object> data, int depth) {
        requireDepth(depth);
        Struct.Builder builder = Struct.newBuilder();
        data.forEach((key, value) -> builder.putFields(key, toValue(value, depth + 1)));
        return builder.build();
    }

    private Value toValue(Object value, int depth) {
        requireDepth(depth);
        Value.Builder builder = Value.newBuilder();
        if (value == null) {
            return builder.setNullValue(NullValue.NULL_VALUE).build();
        }
        if (value instanceof String text) {
            return builder.setStringValue(text).build();
        }
        if (value instanceof Boolean bool) {
            return builder.setBoolValue(bool).build();
        }
        if (value instanceof Number number) {
            return builder.setNumberValue(number.doubleValue()).build();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new AgentToolException(AgentToolErrorKind.INTERNAL);
                }
                normalized.put(key, entry.getValue());
            }
            return builder.setStructValue(toStruct(normalized, depth + 1)).build();
        }
        if (value instanceof List<?> list) {
            List<Value> values = new ArrayList<>(list.size());
            for (Object item : list) {
                values.add(toValue(item, depth + 1));
            }
            return builder.setListValue(ListValue.newBuilder().addAllValues(values)).build();
        }
        throw new AgentToolException(AgentToolErrorKind.INTERNAL);
    }

    private void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
    }
}
