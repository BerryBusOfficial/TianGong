package org.wutong.tiangong.user.store;

import lombok.extern.slf4j.Slf4j;
import org.wutong.tiangong.common.util.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 极简 JSON 落盘。<br>
 * <br>
 * <h2>为什么只做这两个动作</h2>
 * 记忆与历史的数据量都很小（几十条），用不上数据库。真正需要保证的只有三件事：
 * <ol>
 *     <li><b>目录不存在要能自己建</b> —— 首次运行不该要求用户手工 mkdir</li>
 *     <li><b>坏文件不能让应用起不来</b> —— 手改坏了或写到一半断电，应退回空状态
 *         并告警，而不是启动即崩</li>
 *     <li><b>写文件要原子</b> —— 先写临时文件再 move，避免崩溃后留下一个
 *         「能读但内容残缺」的文件（那比文件不存在更难排查）</li>
 * </ol>
 *
 * <h2>为什么只收 {@code Class} 不收 {@code TypeReference}</h2>
 * 顶层结构一律是<b>快照对象</b>（{@code MemorySnapshot} / {@code HistorySnapshot}），
 * 而不是裸 {@code List}。这样反序列化只需 {@code Class}，不需要泛型引用；
 * 附带好处是文件以后加字段时，老文件仍能被读成「字段为默认值的对象」，
 * 裸 List 则可能因类型变化直接解析失败。
 *
 * <h2>不做的</h2>
 * 不做并发锁：写入都发生在节点里，串行。不做加密：本地宠物数据，
 * 不值得为此增加恢复成本。
 */
@Slf4j
public final class JsonFileStore {

    private JsonFileStore() {
    }

    /** 读取；文件不存在、为空或内容损坏时返回空，不抛异常 */
    public static <T> Optional<T> read(Path path, Class<T> type) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(JsonUtil.fromJson(json, type));
        } catch (IOException | RuntimeException e) {
            log.warn("读取文件失败，按空状态继续: {} ({})", path, e.getMessage());
            return Optional.empty();
        }
    }

    /** 原子写入：临时文件 → move。失败只告警，不中断对话 */
    public static void write(Path path, Object value) {
        if (path == null || value == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = JsonUtil.toJson(value);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // 落盘失败不能中断对话：桌宠照样要陪聊，只是这次记不住
            log.warn("写入文件失败: {} ({})", path, e.getMessage());
        }
    }
}
