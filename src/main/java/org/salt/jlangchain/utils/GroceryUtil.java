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

public class GroceryUtil {

    public static boolean isPlainObject(Object obj) {
        if (obj == null) {
            return false; // 或者根据需求返回 true
        }
        Class<?> clazz = obj.getClass();
        return !clazz.equals(Boolean.class) &&
                !clazz.equals(Character.class) &&
                !clazz.equals(Byte.class) &&
                !clazz.equals(Short.class) &&
                !clazz.equals(Integer.class) &&
                !clazz.equals(Long.class) &&
                !clazz.equals(Float.class) &&
                !clazz.equals(Double.class) &&
                !clazz.isArray() &&
                !clazz.equals(String.class);
    }

    public static boolean isBaseType(Object obj) {
        if (obj == null) {
            return false; // 或者根据需求返回 true
        }
        Class<?> clazz = obj.getClass();
        return clazz.equals(Boolean.class) ||
                clazz.equals(Character.class) ||
                clazz.equals(Byte.class) ||
                clazz.equals(Short.class) ||
                clazz.equals(Integer.class) ||
                clazz.equals(Long.class) ||
                clazz.equals(Float.class) ||
                clazz.equals(Double.class) ||
                clazz.equals(String.class);
    }
}
