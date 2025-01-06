/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.jlangchain.core.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.parser.generation.Generation;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class JsonOutputParser extends BaseCumulativeTransformOutputParser {
    @Override
    protected Generation parse(Generation input) {

        if (input instanceof ChatGenerationChunk chatGenerationChunk) {
            if (chatGenerationChunk.getMessage() == null) {
                return chatGenerationChunk;
            }
            String trimmedInput = chatGenerationChunk.getMessage().getContent().trim();
            if (isJson(trimmedInput)) {
                String text = JsonUtil.compactJson(parsePartialJson(trimmedInput));
                chatGenerationChunk.getMessage().setContent(text);
                chatGenerationChunk.setText(text);
                return chatGenerationChunk;
            }

            String json =  processInput(trimmedInput);
            if (StringUtils.isEmpty(json)) {
                chatGenerationChunk.getMessage().setContent("");
                chatGenerationChunk.setText("");
                return chatGenerationChunk;
            }
            String text = JsonUtil.compactJson(parsePartialJson(json));
            chatGenerationChunk.getMessage().setContent(text);
            chatGenerationChunk.setText(text);
            return chatGenerationChunk;
        } else {
            throw new RuntimeException("Unsupported input type: " + input.getClass().getName());
        }
    }

    public static boolean isJson(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }

        String trimmedInput = input.trim();
        char firstChar = trimmedInput.charAt(0);

        return firstChar == '{' || firstChar == '[';
    }

    public static String parsePartialJson(String json) {
        StringBuilder newChars = new StringBuilder();
        Stack<Character> stack = new Stack<>();
        boolean isInsideString = false;
        boolean escaped = false;

        for (char c : json.toCharArray()) {
            if (isInsideString) {
                if (c == '"' && !escaped) {
                    isInsideString = false;
                } else if (c == '\n' && !escaped) {
                    // 替换换行符为转义符
                    newChars.append("\\n");
                    continue;
                } else if (c == '\\') {
                    escaped = !escaped;
                } else {
                    escaped = false;
                }
            } else {
                if (c == '"') {
                    isInsideString = true;
                    escaped = false;
                } else if (c == '{') {
                    stack.push('}');
                } else if (c == '[') {
                    stack.push(']');
                } else if (c == '}' || c == ']') {
                    if (!stack.isEmpty() && stack.peek() == c) {
                        stack.pop();
                    } else {
                        return null;
                    }
                }
            }
            newChars.append(c);
        }

        if (isInsideString) {
            newChars.append('"');
        }

        while (!stack.isEmpty()) {
            newChars.append(stack.pop());
        }

        return newChars.toString();
    }

    public static String processInput(String input) {
        Pattern pattern = Pattern.compile("```(json)?(.*)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);

        if (matcher.find()) {
            String match = matcher.group(0).trim();
            int startPoint = match.indexOf("```");
            int startJson = match.indexOf("```json");
            int start = startJson != -1 ? startJson + 7 : startPoint + 3;
            int end = match.indexOf("```", start) ;
            return match.substring(start, end != -1 ? end : match.length());
        }

        return "";
    }
}
