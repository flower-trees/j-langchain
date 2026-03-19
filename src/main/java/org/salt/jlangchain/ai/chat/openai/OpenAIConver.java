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

package org.salt.jlangchain.ai.chat.openai;

import org.salt.jlangchain.ai.chat.openai.param.OpenAIRequest;
import org.salt.jlangchain.ai.chat.openai.param.OpenAIResponse;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.enums.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OpenAIConver {

    protected static OpenAIRequest convertRequest(AiChatInput aiChatInput) {
        OpenAIRequest openAIRequest = new OpenAIRequest();
        openAIRequest.setModel(aiChatInput.getModel());
        openAIRequest.setStream(aiChatInput.isStream());

        if (!CollectionUtils.isEmpty(aiChatInput.getMessages())) {
            List<OpenAIRequest.Message> chatGPTMessages = aiChatInput.getMessages().stream()
                    .map(OpenAIConver::convertMessage)
                    .collect(Collectors.toList());
            openAIRequest.setMessages(chatGPTMessages);
        }

        openAIRequest.setInput(aiChatInput.getInput());

        // Basic generation parameters
        openAIRequest.setTemperature(aiChatInput.getTemperature());
        openAIRequest.setTopP(aiChatInput.getTopP());
        openAIRequest.setMaxTokens(aiChatInput.getMaxTokens());
        openAIRequest.setPresencePenalty(aiChatInput.getPresencePenalty());
        openAIRequest.setFrequencyPenalty(aiChatInput.getFrequencyPenalty());
        openAIRequest.setStop(aiChatInput.getStop());
        openAIRequest.setN(aiChatInput.getN());
        openAIRequest.setSuffix(aiChatInput.getSuffix());
        openAIRequest.setLogprobs(aiChatInput.getLogprobs());
        openAIRequest.setTopLogprobs(aiChatInput.getTopLogprobs());
        openAIRequest.setEcho(aiChatInput.getEcho());

        // Tool calls and MCP support
        if (!CollectionUtils.isEmpty(aiChatInput.getTools())) {
            List<OpenAIRequest.Tool> tools = aiChatInput.getTools().stream()
                    .map(OpenAIConver::convertTool)
                    .collect(Collectors.toList());
            openAIRequest.setTools(tools);
        }
        openAIRequest.setToolChoice(aiChatInput.getToolChoice());
        openAIRequest.setParallelToolCalls(aiChatInput.getParallelToolCalls());
        openAIRequest.setMcpConfig(convertMcpConfig(aiChatInput.getMcpConfig()));

        // Response format control
        openAIRequest.setResponseFormat(convertResponseFormat(aiChatInput.getResponseFormat()));
        openAIRequest.setResponseSchema(aiChatInput.getResponseSchema());

        return openAIRequest;
    }

    private static OpenAIRequest.Message convertMessage(AiChatInput.Message aiChatMessage) {
        OpenAIRequest.Message chatGPTMessage = new OpenAIRequest.Message();
        chatGPTMessage.setRole(aiChatMessage.getRole());
        chatGPTMessage.setContent(aiChatMessage.getContent());
        chatGPTMessage.setName(aiChatMessage.getName());
        chatGPTMessage.setToolCallId(aiChatMessage.getToolCallId());
        chatGPTMessage.setMetadata(aiChatMessage.getMetadata());

        if (!CollectionUtils.isEmpty(aiChatMessage.getToolCalls())) {
            List<OpenAIRequest.ToolCall> toolCalls = aiChatMessage.getToolCalls().stream()
                    .map(OpenAIConver::convertToolCall)
                    .collect(Collectors.toList());
            chatGPTMessage.setToolCalls(toolCalls);
        }

        return chatGPTMessage;
    }

    public static AiChatOutput convertResponse(OpenAIResponse response) {
        AiChatOutput aiChatOutput = new AiChatOutput();

        aiChatOutput.setId(response.getId());

        //get messages
        List<AiChatOutput.Message> messages = getMessages(response);
        aiChatOutput.setMessages(messages);

        List<AiChatOutput.DataObject> data = getData(response);
        aiChatOutput.setData(data);

        // Handle MCP response
        if (response.getMcpResponse() != null) {
            aiChatOutput.setMcpResponse(convertMcpResponse(response.getMcpResponse()));
        }

        // Handle error response
        if (response.getError() != null) {
            aiChatOutput.setCode("error");
            aiChatOutput.setMessage(response.getError().getMessage());
        } else {
            //get finish reason
            if (!CollectionUtils.isEmpty(response.getChoices())
                    && response.getChoices().get(0).getFinishReason() != null
                    && response.getChoices().get(0).getFinishReason().equals(AiChatCode.STOP.getCode())) {
                aiChatOutput.setCode(AiChatCode.STOP.getCode());
            } else {
                aiChatOutput.setCode(AiChatCode.MESSAGE.getCode());
            }
        }

        return aiChatOutput;
    }

    private static List<AiChatOutput.Message> getMessages(OpenAIResponse response) {
        List<AiChatOutput.Message> messages = new ArrayList<>();
        if (!CollectionUtils.isEmpty(response.getChoices()) && response.getChoices().get(0).getDelta() != null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            OpenAIResponse.Choice.Delta delta = response.getChoices().get(0).getDelta();
            message.setRole(delta.getRole());
            message.setContent(delta.getContent());
            message.setType(MessageType.MARKDOWN.getCode());

            // Handle tool calls in delta
            if (!CollectionUtils.isEmpty(delta.getToolCalls())) {
                List<AiChatOutput.ToolCall> toolCalls = delta.getToolCalls().stream()
                        .map(OpenAIConver::convertResponseToolCall)
                        .collect(Collectors.toList());
                message.setToolCalls(toolCalls);
            }

            messages.add(message);
        } else if (!CollectionUtils.isEmpty(response.getChoices()) && response.getChoices().get(0).getMessage()!= null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            OpenAIResponse.Choice.Message chatGPTMessage = response.getChoices().get(0).getMessage();
            message.setRole(chatGPTMessage.getRole());
            message.setContent(chatGPTMessage.getContent());
            message.setType(MessageType.MARKDOWN.getCode());
            message.setName(chatGPTMessage.getName());
            message.setMetadata(chatGPTMessage.getMetadata());

            // Handle tool calls in message
            if (!CollectionUtils.isEmpty(chatGPTMessage.getToolCalls())) {
                List<AiChatOutput.ToolCall> toolCalls = chatGPTMessage.getToolCalls().stream()
                        .map(OpenAIConver::convertResponseToolCall)
                        .collect(Collectors.toList());
                message.setToolCalls(toolCalls);
            }

            // Handle function call (deprecated)
            if (chatGPTMessage.getFunctionCall() != null) {
                AiChatOutput.FunctionCall functionCall = new AiChatOutput.FunctionCall();
                functionCall.setName(chatGPTMessage.getFunctionCall().getName());
                functionCall.setArguments(chatGPTMessage.getFunctionCall().getArguments());
                message.setFunctionCall(functionCall);
            }

            messages.add(message);
        }
        return messages;
    }

    public static List<AiChatOutput.DataObject> getData(OpenAIResponse response) {
        if (response.getData() != null) {
            return response.getData().stream().map(dataObject -> {
                AiChatOutput.DataObject data = new AiChatOutput.DataObject();
                data.setEmbedding(dataObject.getEmbedding());
                data.setIndex(dataObject.getIndex());
                data.setObject(dataObject.getObject());
                return data;
            }).toList();
        }
        return List.of();
    }

    private static OpenAIRequest.Tool convertTool(AiChatInput.Tool tool) {
        OpenAIRequest.Tool openAITool = new OpenAIRequest.Tool();
        openAITool.setType(tool.getType());

        if (tool.getFunction() != null) {
            OpenAIRequest.Tool.FunctionTool functionTool = new OpenAIRequest.Tool.FunctionTool();
            functionTool.setName(tool.getFunction().getName());
            functionTool.setDescription(tool.getFunction().getDescription());
            functionTool.setParameters(tool.getFunction().getParameters());
            functionTool.setStrict(tool.getFunction().getStrict());
            openAITool.setFunction(functionTool);
        }

        return openAITool;
    }

    private static OpenAIRequest.ToolCall convertToolCall(AiChatInput.ToolCall toolCall) {
        OpenAIRequest.ToolCall openAIToolCall = new OpenAIRequest.ToolCall();
        openAIToolCall.setId(toolCall.getId());
        openAIToolCall.setType(toolCall.getType());

        if (toolCall.getFunction() != null) {
            OpenAIRequest.ToolCall.FunctionCall functionCall = new OpenAIRequest.ToolCall.FunctionCall();
            functionCall.setName(toolCall.getFunction().getName());
            functionCall.setArguments(toolCall.getFunction().getArguments());
            openAIToolCall.setFunction(functionCall);
        }

        return openAIToolCall;
    }

    private static AiChatOutput.ToolCall convertResponseToolCall(OpenAIResponse.Choice.ToolCall toolCall) {
        AiChatOutput.ToolCall outputToolCall = new AiChatOutput.ToolCall();
        outputToolCall.setId(toolCall.getId());
        outputToolCall.setType(toolCall.getType());
        outputToolCall.setIndex(toolCall.getIndex());

        if (toolCall.getFunction() != null) {
            AiChatOutput.ToolCall.Function function = new AiChatOutput.ToolCall.Function();
            function.setName(toolCall.getFunction().getName());
            function.setArguments(toolCall.getFunction().getArguments());
            outputToolCall.setFunction(function);
        }

        return outputToolCall;
    }

    private static OpenAIRequest.McpConfig convertMcpConfig(AiChatInput.McpConfig mcpConfig) {
        if (mcpConfig == null) return null;

        OpenAIRequest.McpConfig openAIMcpConfig = new OpenAIRequest.McpConfig();
        openAIMcpConfig.setServerUrl(mcpConfig.getServerUrl());
        openAIMcpConfig.setVersion(mcpConfig.getVersion());
        openAIMcpConfig.setCapabilities(mcpConfig.getCapabilities());
        openAIMcpConfig.setTimeout(mcpConfig.getTimeout());
        openAIMcpConfig.setRetryCount(mcpConfig.getRetryCount());
        openAIMcpConfig.setEnabledTools(mcpConfig.getEnabledTools());

        if (mcpConfig.getAuth() != null) {
            OpenAIRequest.McpConfig.AuthConfig authConfig = new OpenAIRequest.McpConfig.AuthConfig();
            authConfig.setType(mcpConfig.getAuth().getType());
            authConfig.setToken(mcpConfig.getAuth().getToken());
            authConfig.setHeaders(mcpConfig.getAuth().getHeaders());
            openAIMcpConfig.setAuth(authConfig);
        }

        return openAIMcpConfig;
    }

    private static OpenAIRequest.ResponseFormat convertResponseFormat(AiChatInput.ResponseFormat responseFormat) {
        if (responseFormat == null) return null;

        OpenAIRequest.ResponseFormat openAIResponseFormat = new OpenAIRequest.ResponseFormat();
        openAIResponseFormat.setType(responseFormat.getType());

        if (responseFormat.getJsonSchema() != null) {
            OpenAIRequest.ResponseFormat.JsonSchema jsonSchema = new OpenAIRequest.ResponseFormat.JsonSchema();
            jsonSchema.setName(responseFormat.getJsonSchema().getName());
            jsonSchema.setSchema(responseFormat.getJsonSchema().getSchema());
            jsonSchema.setStrict(responseFormat.getJsonSchema().getStrict());
            openAIResponseFormat.setJsonSchema(jsonSchema);
        }

        return openAIResponseFormat;
    }

    private static AiChatOutput.McpResponse convertMcpResponse(OpenAIResponse.McpResponse mcpResponse) {
        if (mcpResponse == null) return null;

        AiChatOutput.McpResponse outputMcpResponse = new AiChatOutput.McpResponse();
        outputMcpResponse.setStatus(mcpResponse.getStatus());
        outputMcpResponse.setExecutionTime(mcpResponse.getExecutionTime());

        if (mcpResponse.getServerInfo() != null) {
            AiChatOutput.McpResponse.ServerInfo serverInfo = new AiChatOutput.McpResponse.ServerInfo();
            serverInfo.setName(mcpResponse.getServerInfo().getName());
            serverInfo.setVersion(mcpResponse.getServerInfo().getVersion());
            serverInfo.setProtocolVersion(mcpResponse.getServerInfo().getProtocolVersion());
            serverInfo.setCapabilities(mcpResponse.getServerInfo().getCapabilities());
            outputMcpResponse.setServerInfo(serverInfo);
        }

        if (!CollectionUtils.isEmpty(mcpResponse.getToolResults())) {
            List<AiChatOutput.McpResponse.ToolResult> toolResults = mcpResponse.getToolResults().stream()
                    .map(tr -> {
                        AiChatOutput.McpResponse.ToolResult toolResult = new AiChatOutput.McpResponse.ToolResult();
                        toolResult.setToolName(tr.getToolName());
                        toolResult.setStatus(tr.getStatus());
                        toolResult.setResult(tr.getResult());
                        toolResult.setError(tr.getError());
                        toolResult.setExecutionTime(tr.getExecutionTime());
                        return toolResult;
                    })
                    .collect(Collectors.toList());
            outputMcpResponse.setToolResults(toolResults);
        }

        return outputMcpResponse;
    }
}
