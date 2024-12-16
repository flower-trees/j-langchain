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

package org.salt.jlangchain.ai.vendor;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

@Getter
public enum VendorType {

    CHATGPT("chatgpt"),
    DOUBAO("doubao"),
    ALIYUN("aliyun"),
    MOONSHOT("moonshot"),
    OLLAMA("ollama"),
    ;

    private final String code;

    VendorType(String type) {
        this.code = type;
    }

    public static VendorType fromCode(String type) {
        for (VendorType it : VendorType.values()) {
            if (it.getCode().equalsIgnoreCase(type)) {
                return it;
            }
        }
        throw new IllegalArgumentException(VendorType.class.getName() + " Invalid code: " + type);
    }

    public boolean equalsV(String code) {
        return StringUtils.equalsIgnoreCase(this.code, code);
    }
}
