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

package org.salt.jlangchain.utils;

import java.util.HashMap;
import java.util.Map;

public class PromptUtil {

    public static Map<String, String> stringToMap(String input) {
        Map<String, String> resultMap = new HashMap<>();
        String[] lines = input.split("\n");
        for (String line : lines) {
            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                resultMap.put(parts[0].trim(), parts[1].trim());
            }
        }
        return resultMap;
    }
}