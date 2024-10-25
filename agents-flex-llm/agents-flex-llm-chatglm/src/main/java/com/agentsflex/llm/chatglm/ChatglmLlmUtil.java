/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.llm.chatglm;

import com.agentsflex.core.llm.ChatOptions;
import com.agentsflex.core.message.MessageStatus;
import com.agentsflex.core.parser.AiMessageParser;
import com.agentsflex.core.parser.FunctionMessageParser;
import com.agentsflex.core.parser.impl.DefaultAiMessageParser;
import com.agentsflex.core.parser.impl.DefaultFunctionMessageParser;
import com.agentsflex.core.prompt.DefaultPromptFormat;
import com.agentsflex.core.prompt.Prompt;
import com.agentsflex.core.prompt.PromptFormat;
import com.agentsflex.core.util.Maps;
import com.alibaba.fastjson.JSON;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.MacAlgorithm;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

public class ChatglmLlmUtil {

    private static final PromptFormat promptFormat = new DefaultPromptFormat();

    private static final String id = "HS256";
    private static final String jcaName = "HmacSHA256";
    private static final MacAlgorithm macAlgorithm;

    static {
        try {
            //create a custom MacAlgorithm with a custom minKeyBitLength
            int minKeyBitLength = 128;
            Class<?> c = Class.forName("io.jsonwebtoken.impl.security.DefaultMacAlgorithm");
            Constructor<?> ctor = c.getDeclaredConstructor(String.class, String.class, int.class);
            ctor.setAccessible(true);
            macAlgorithm = (MacAlgorithm) ctor.newInstance(id, jcaName, minKeyBitLength);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException |
                 InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }


    public static String createAuthorizationToken(ChatglmLlmConfig config) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("alg", "HS256");
        headers.put("sign_type", "SIGN");

        long nowMillis = System.currentTimeMillis();
        String[] idAndSecret = config.getApiKey().split("\\.");

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("api_key", idAndSecret[0]);
        payloadMap.put("exp", nowMillis + 3600000);
        payloadMap.put("timestamp", nowMillis);
        String payloadJsonString = JSON.toJSONString(payloadMap);

        byte[] bytes = idAndSecret[1].getBytes();
        SecretKey secretKey = new SecretKeySpec(bytes, jcaName);

        JwtBuilder builder = Jwts.builder()
            .content(payloadJsonString)
            .header().add(headers).and()
            .signWith(secretKey, macAlgorithm);
        return builder.compact();
    }

    public static AiMessageParser getAiMessageParser(boolean isStream) {
        DefaultAiMessageParser aiMessageParser = new DefaultAiMessageParser();
        if (isStream) {
            aiMessageParser.setContentPath("$.choices[0].delta.content");
        } else {
            aiMessageParser.setContentPath("$.choices[0].message.content");
        }

        aiMessageParser.setIndexPath("$.choices[0].index");
        aiMessageParser.setStatusPath("$.choices[0].finish_reason");
        aiMessageParser.setStatusParser(content -> parseMessageStatus((String) content));
        aiMessageParser.setTotalTokensPath("$.usage.total_tokens");
        aiMessageParser.setPromptTokensPath("$.usage.prompt_tokens");
        aiMessageParser.setCompletionTokensPath("$.usage.completion_tokens");
        return aiMessageParser;
    }


    public static FunctionMessageParser getFunctionMessageParser() {
        DefaultFunctionMessageParser functionMessageParser = new DefaultFunctionMessageParser();
        functionMessageParser.setFunctionNamePath("$.choices[0].message.tool_calls[0].function.name");
        functionMessageParser.setFunctionArgsPath("$.choices[0].message.tool_calls[0].function.arguments");
        functionMessageParser.setFunctionArgsParser(JSON::parseObject);
        return functionMessageParser;
    }


    public static String promptToPayload(Prompt<?> prompt, ChatglmLlmConfig config, boolean withStream, ChatOptions options) {
        Maps.Builder builder = Maps.of("model", config.getModel())
            .put("messages", promptFormat.toMessagesJsonObject(prompt))
            .putIf(withStream, "stream", true)
            .putIfNotEmpty("tools", promptFormat.toFunctionsJsonObject(prompt))
            .putIfContainsKey("tools", "tool_choice", "auto")
            .putIfNotNull("top_p", options.getTopP())
            .putIfNotEmpty("stop", options.getStop())
            .putIf(map -> !map.containsKey("tools") && options.getTemperature() > 0, "temperature", options.getTemperature())
            .putIf(map -> !map.containsKey("tools") && options.getMaxTokens() != null, "max_tokens", options.getMaxTokens());
        return JSON.toJSONString(builder.build());

    }


    public static MessageStatus parseMessageStatus(String status) {
        return "stop".equals(status) ? MessageStatus.END : MessageStatus.MIDDLE;
    }


}
