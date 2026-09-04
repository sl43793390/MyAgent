package com.agent.core.memory;

import com.agent.core.model.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用于将以 JSON 形式持久化消息的记忆的共享 Jackson 配置。
 *
 * <p>{@code ACCEPT_CASE_INSENSITIVE_ENUMS} 之所以重要，是因为 {@code Role} 在实际情况中会以
 * 多种方式写入存储（{@code SYSTEM}、{@code system}）；若没有它，读回旧版本写入的历史时会
 * 抛出枚举反序列化错误。
 */
final class JsonSupport {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonSupport() {
    }

    static String write(Message message) throws JsonProcessingException {
        return MAPPER.writeValueAsString(message);
    }

    static Message read(String json) throws JsonProcessingException {
        return MAPPER.readValue(json, Message.class);
    }
}
