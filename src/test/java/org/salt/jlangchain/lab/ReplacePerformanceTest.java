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

package org.salt.jlangchain.lab;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReplacePerformanceTest {
    private static String replaceNewLineOriginal(String input) {
        if (input == null) {
            return "";
        }

        input = input.replace("\n", "\\\\n");
        input = input.replace("\t", "\\\\t");
        input = input.replace("\"", "\\\"");
        return input;
    }

    private static String replaceNewLineWithMatcher(String input) {
        if (input == null) {
            return "";
        }

        // 正则表达式匹配 \n, \t, 和 "
        Pattern pattern = Pattern.compile("[\n\t\"]");
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String replacement;
            switch (matcher.group()) {
                case "\n": replacement = "\\\\n"; break;
                case "\t": replacement = "\\\\t"; break;
                case "\"": replacement = "\\\\\""; break;
                default: replacement = matcher.group(); break;
            }
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    public static void main(String[] args) {

        StringBuilder testString = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 10000; i++) {
            int choice = random.nextInt(4);
            switch (choice) {
                case 0 -> testString.append('\n');
                case 1 -> testString.append('\t');
                case 2 -> testString.append('"');
                default -> testString.append((char) ('a' + random.nextInt(26)));
            }
        }

        String input = testString.toString();

        long start = System.nanoTime();
        replaceNewLineOriginal(input);
        long end = System.nanoTime();
        System.out.println("Original Method Time: " + (end - start) + " ns");

        start = System.nanoTime();
        replaceNewLineWithMatcher(input);
        end = System.nanoTime();
        System.out.println("Regex Method Time: " + (end - start) + " ns");
    }
}
