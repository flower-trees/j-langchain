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

package org.salt.jlangchain.core.tts;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.function.flow.context.ContextBus;
import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.event.EventAction;
import org.salt.jlangchain.core.event.EventMessageChunk;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.BaseMessageChunk;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.parser.generation.Generation;
import org.salt.jlangchain.core.tts.card.Card;
import org.salt.jlangchain.core.tts.card.TtsCard;
import org.salt.jlangchain.core.tts.card.TtsCardChunk;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class TtsBase extends BaseRunnable<TtsCard, Object> {

    Map<String, Object> config = Map.of(
            "run_name", this.getClass().getSimpleName(),
            "tags", List.of()
    );
    EventAction eventAction = new EventAction("tts");

    protected StringBuilder cumulate = new StringBuilder();

    protected Lock lock = new ReentrantLock();
    protected Condition condition = lock.newCondition();

    protected PriorityBlockingQueue<PriorityTtsCardChunk> queue = new PriorityBlockingQueue<>();

    protected final ExecutorService executor = Executors.newSingleThreadExecutor();

    int ttsIndex = 0;

    @Override
    public TtsCard invoke(Object input) {
        if (input instanceof String stringPrompt) {
            return parseResult(new Card(stringPrompt));
        } else if (input instanceof BaseMessage baseMessage) {
            return parseResult(new Card(baseMessage.getContent()));
        } else if (input instanceof Generation generation) {
            return parseResult(new Card(generation.getText()));
        } else {
            throw new RuntimeException("Unsupported input type: " + input.getClass().getName());
        }
    }

    @Override
    public TtsCardChunk stream(Object input) {
        return transform(input);
    }

    @Override
    public EventMessageChunk streamEvent(Object input) {
        EventMessageChunk eventMessageChunk = new EventMessageChunk();

        ContextBus.create(input);
        getContextBus().putTransmit(CallInfo.EVENT.name(), true);
        getContextBus().putTransmit(CallInfo.EVENT_CHAIN.name(), false);
        getContextBus().putTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name(), eventMessageChunk);

        TtsCardChunk chunk = stream(input);
        chunk.ignore();

        return eventMessageChunk;
    }

    protected TtsCardChunk transform(Object input) {
        if (input instanceof String stringPrompt) {
            TtsCardChunk ttsCardChunk = new TtsCardChunk(stringPrompt, true);
            transformAsync(input, ttsCardChunk.getIterator(), ttsCardChunk);
            return ttsCardChunk;
        } else if (input instanceof BaseMessageChunk<? extends BaseMessage> baseMessageChunk){
            if (baseMessageChunk instanceof AIMessageChunk aiMessageChunk){
                TtsCardChunk ttsCardChunk = new TtsCardChunk(aiMessageChunk.getContent(), aiMessageChunk.isLast());
                transformAsync(input, baseMessageChunk.getIterator(), ttsCardChunk);
                return ttsCardChunk;
            } else {
                throw new RuntimeException("Unsupported message type: " + baseMessageChunk.getClass().getName());
            }
        } else if (input instanceof ChatGenerationChunk chatGenerationChunk){
            TtsCardChunk ttsCardChunk = new TtsCardChunk(chatGenerationChunk.getText(), chatGenerationChunk.isLast());
            transformAsync(input, chatGenerationChunk.getIterator(), ttsCardChunk);
            return ttsCardChunk;
        } else {
            throw new RuntimeException("Unsupported input type: " + input.getClass().getName());
        }
    }

    protected void transformAsync(Object input, Iterator<?> iterator, TtsCardChunk result) {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(
                () -> {
                    eventAction.eventStart(input, config);
                    int index = 10000;
                    try {
                        while (iterator.hasNext()) {
                            Object chunk = iterator.next();
                            log.debug("chunk: {}", JsonUtil.toJson(chunk));
                            if (chunk instanceof AIMessageChunk aiMessageChunk) {
                                TtsCardChunk ttsCardChunk = new TtsCardChunk(aiMessageChunk.getContent(), aiMessageChunk.isLast());
                                TtsCard ttsCard = parseResult(ttsCardChunk);
                                TtsCardChunk resultChunk = (TtsCardChunk) ttsCard;
                                if (StringUtils.isNotEmpty(resultChunk.getText()) || resultChunk.isLast()) {
                                    resultChunk.setLast(false);
                                    log.debug("offer aiMessageChunk: {}", JsonUtil.toJson(resultChunk));
                                    queue.add(new PriorityTtsCardChunk(resultChunk, index++));
                                    eventAction.eventStream(resultChunk, config);
                                }
                            } else if (chunk instanceof ChatGenerationChunk chatGenerationChunk) {
                                TtsCardChunk ttsCardChunk = new TtsCardChunk(chatGenerationChunk.getText(), chatGenerationChunk.isLast());
                                TtsCard ttsCard = parseResult(ttsCardChunk);
                                TtsCardChunk resultChunk = (TtsCardChunk) ttsCard;
                                if (StringUtils.isNotEmpty(resultChunk.getText()) || resultChunk.isLast()) {
                                    resultChunk.setLast(false);
                                    log.debug("offer chatGenerationChunk: {}", JsonUtil.toJson(resultChunk));
                                    queue.add(new PriorityTtsCardChunk(resultChunk, index++));
                                    eventAction.eventStream(resultChunk, config);
                                }
                            } else {
                                throw new RuntimeException("Unsupported message type: " + chunk.getClass().getName());
                            }
                        }
                    } catch (TimeoutException e) {
                        log.error("transformAsync timeout:", e);
                        throw new RuntimeException(e);
                    }
                    eventAction.eventEnd(result, config);
                }
        );

        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(
            () -> {

                log.debug("transformAsync next start");

                await();

                log.debug("transformAsync next wait off");

                try {
                    PriorityTtsCardChunk wrappedChunk;
                    do {
                        wrappedChunk = queue.poll(10000, TimeUnit.MILLISECONDS);
                        if (wrappedChunk != null) {
                            log.debug("poll chunk: {}", wrappedChunk.getChunk().getText());
                            if (!wrappedChunk.getChunk().isAudio()) {
                                result.add(wrappedChunk.getChunk()); // add tts chunk cumulate
                            }
                            result.getIterator().append(wrappedChunk.getChunk());
                        }
                    } while (wrappedChunk != null && !wrappedChunk.getChunk().isLast());
                } catch (InterruptedException e) {
                    log.warn("wait interrupted:", e);
                } catch (TimeoutException e) {
                    log.error("transformAsync timeout:", e);
                    throw new RuntimeException(e);
                }
            }
        );
    }

    protected TtsCard parseResult(Card card) {

        if (card.getClass().equals(Card.class)) {
            log.debug("parse card: {}", JsonUtil.toJson(card));
            return callTts(card.getText());
        }

        log.debug("parse card chunk: {}", JsonUtil.toJson(card));

        TtsCardChunk ttsCardChunk = (TtsCardChunk) card;

        boolean isLast = ttsCardChunk.isLast();

        if (isLast) {

            if (!cumulate.isEmpty()) {
                log.debug("submit tts last start");
                final int index = ++ttsIndex;
                executor.submit(TheadHelper.getDecoratorAsync(() -> {
                    TtsCard ttsCard = callTts(cumulate.toString());
                    TtsCardChunk ttsChunk = new TtsCardChunk(index, ttsCard.getText(), ttsCard.getBase64(), ttsCard.isAudio(), true);
                    log.debug("submit offer last ttsChunk: {}", ttsChunk.getText());
                    queue.add(new PriorityTtsCardChunk(ttsChunk, Integer.MAX_VALUE));
                    signal();
                }));
            } else {
                TtsCardChunk endChunk = new TtsCardChunk(ttsCardChunk.getIndex(), ttsCardChunk.getText(), ttsCardChunk.getBase64(), ttsCardChunk.isAudio(), true);
                executor.submit(TheadHelper.getDecoratorAsync(() -> {
                    log.debug("submit tts last: {}", endChunk);
                    queue.add(new PriorityTtsCardChunk(endChunk, Integer.MAX_VALUE));
                    signal();
                }));
            }
            return ttsCardChunk;
        }

        String text = ttsCardChunk.getText();

        if (StringUtils.isBlank(text)) {
            return ttsCardChunk;
        }

        if (isPunctuation(text) && cumulate.length() > 20) {

            cumulate.append(text);

            log.debug("parse punctuation: {}", cumulate);

            String sentence = cumulate.toString();

            ttsIndex++;

            final int index = ttsIndex;

            // 提交到单线程池执行
            executor.submit(TheadHelper.getDecoratorAsync(() -> {
                try {
                    log.debug("submit tts start");

                    TtsCard ttsCard = callTts(sentence);
                    TtsCardChunk ttsChunk = new TtsCardChunk(index, ttsCard.getText(), ttsCard.getBase64(), ttsCard.isAudio(), false);
                    log.debug("submit offer ttsChunk: {}", ttsChunk.getText());
                    queue.add(new PriorityTtsCardChunk(ttsChunk, ttsIndex));
                    signal();

                    log.debug("submit tts end");

                } catch (Exception e) {
                    log.error("submit tts fail: {}", e.getMessage(), e);
                }
            }));

            cumulate = new StringBuilder();

        } else {
            cumulate.append(text);
        }
        return ttsCardChunk;
    }

    public abstract TtsCard callTts(String text);

    private boolean isPunctuation(String text) {
        if (text == null || text.isEmpty()) return false;
//        String regex = "[\\p{Punct}，。！？；：……￥]";
        String regex = "[\\p{Punct}\\s，。！？；：……￥]";
        return text.matches(regex);
    }

    protected static class PriorityTtsCardChunk implements Comparable<PriorityTtsCardChunk> {
        @Getter
        private final TtsCardChunk chunk;
        private final int priority; // 优先级数值

        public PriorityTtsCardChunk(TtsCardChunk chunk, int priority) {
            this.chunk = chunk;
            this.priority = priority;
        }

        @Override
        public int compareTo(PriorityTtsCardChunk other) {
            // 数值越小优先级越高
            return Integer.compare(this.priority, other.priority);
        }
    }

    protected void await() {
        log.debug("tts await");

        lock.lock();
        try {
            boolean isWait = condition.await(10000, TimeUnit.MILLISECONDS);  // 使用 Condition 的 await
            if (!isWait) {
                log.warn("transformAsync next wait on false");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    protected void signal() {
        log.debug("tts signal");

        lock.lock();
        try {
            condition.signal();
        } finally {
            lock.unlock();
        }
    }
}