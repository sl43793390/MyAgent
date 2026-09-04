package com.agent.core.memory;

import com.agent.core.llm.LLMClient;
import com.agent.core.llm.LLMParams;
import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 借助 LLM 对会话进行摘要，把一段较长的对话压缩成较短的对话。
 *
 * <p>这套逻辑原本<i>内嵌</i>在每个 {@code Memory} 实现之中：存储接口上带有
 * {@code setCompressionLLMClient(...)}，三个实现各自都包含约 60 行雷同的代码——
 * "拆分系统消息、构造提示词、调用 LLM、重建消息列表"。这导致存储层不得不依赖 LLM 客户端，
 * 而每新增一个后端就意味着再把这段代码复制粘贴一遍。
 *
 * <p>将其抽取出来之后，{@link Memory} 只关心存储本身，压缩则变成一个由
 * {@link AbstractMemory} 调用的可选协作组件。
 *
 * <p>本类不可变且线程安全。
 */
public final class MemoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressor.class);

    /**
     * 默认的指令模板。其中的 {@code {conversation}} 占位符会被替换成渲染后的对话记录。
     */
    public static final String DEFAULT_PROMPT = """
            Please compress the following conversation into a concise summary that preserves key information,
            decisions, and context. The summary should be brief but comprehensive enough to continue the conversation.

            Conversation:
            {conversation}

            Provide a compressed summary:
            """;

    /**
     * 加在生成的摘要之前的前缀标记，使后续的压缩轮次能够识别出它，
     * 并将其并入下一次摘要，从而避免摘要无限累积。
     */
    public static final String SUMMARY_PREFIX = "[对话摘要]\n";

    private final LLMClient llmClient;
    private final String promptTemplate;
    private final LLMParams params;

    public MemoryCompressor(LLMClient llmClient) {
        this(llmClient, DEFAULT_PROMPT, LLMParams.DEFAULT);
    }

    public MemoryCompressor(LLMClient llmClient, String promptTemplate) {
        this(llmClient, promptTemplate, LLMParams.DEFAULT);
    }

    public MemoryCompressor(LLMClient llmClient, String promptTemplate, LLMParams params) {
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient must not be null");
        }
        this.llmClient = llmClient;
        this.promptTemplate = (promptTemplate == null || promptTemplate.isBlank())
                ? DEFAULT_PROMPT
                : promptTemplate;
        this.params = params != null ? params : LLMParams.DEFAULT;
    }

    /**
     * 对一段会话生成摘要。
     *
     * @param conversation 待压缩的消息，从旧到新
     * @return 摘要文本；当压缩未真正发生时返回 null（没有客户端、输入为空、
     *         模型输出为空，或 LLM 调用失败）
     */
    public String summarize(List<Message> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return null;
        }

        String prompt = promptTemplate.replace("{conversation}", render(conversation));
        List<Message> request = List.of(
                Message.system("You are a helpful assistant that compresses conversations."),
                Message.user(prompt));

        try {
            LLMResponse response = llmClient.chat(request, List.of(), params);
            String summary = response == null ? null : response.content();
            if (summary == null || summary.isBlank()) {
                log.warn("Compression LLM returned an empty summary");
                return null;
            }
            return summary.trim();
        } catch (Exception e) {
            // 压缩只是一种优化，绝不应成为智能体运行失败的原因。
            log.error("Conversation compression failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将消息渲染为纯文本的 {@code role: content} 对话记录。为便于测试而设为可见。
     */
    public String render(List<Message> conversation) {
        StringBuilder text = new StringBuilder();
        for (Message message : conversation) {
            if (message == null) {
                continue;
            }
            text.append(message.role().getValue())
                    .append(": ")
                    .append(message.content() != null ? message.content() : "")
                    .append('\n');
        }
        return text.toString();
    }

    /**
     * 判断某条消息是否为此前生成的摘要。
     */
    public static boolean isSummary(Message message) {
        return message != null
                && message.content() != null
                && message.content().startsWith(SUMMARY_PREFIX);
    }

    public LLMClient llmClient() {
        return llmClient;
    }

    public String promptTemplate() {
        return promptTemplate;
    }

    public LLMParams params() {
        return params;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MemoryCompressor that)) return false;
        return llmClient.equals(that.llmClient) && promptTemplate.equals(that.promptTemplate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(llmClient, promptTemplate);
    }
}
