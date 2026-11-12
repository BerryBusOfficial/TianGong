package org.wutong.tiangong.core.scheduled;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 6 字段 Cron 表达式：{@code 秒 分 时 日 月 周}。<br>
 * <br>
 * <h2>方言：QUARTZ（cron-utils 提供）</h2>
 * 解析与求下一时刻由 <b>cron-utils</b>（{@code com.cronutils}，Apache 2.0，运行时只带
 * slf4j-api）承担，方言取 {@link CronType#QUARTZ}。<br>
 * 之前这里是手写实现，代价是边缘语法总差一点（实测日字段的 {@code L} 会跳过所有
 * 不足 31 天的月份），以及「日/周同时指定时是 AND 还是 OR」这种和主流不一致的歧义
 * 语义。cron 属于「自己写总有边缘 case 不对」的东西，交给成熟库更划算。<br>
 * <br>
 * <h2>为了不让现有表达式炸掉，这里做了一层兼容</h2>
 * Quartz 规定<b>「日」和「周」必须有一个是 {@code ?}</b>，但项目里既有的写法
 * （以及 Unix cron 过来的习惯）是两个都写 {@code *}，例如 {@code 0 *&#42;/5 * * * *}。<br>
 * {@link #normalize(String)} 会在解析前把这种情况归一掉：
 * <ul>
 *     <li>日、周都是 {@code *} → 周改 {@code ?}（= 每天）</li>
 *     <li>只有周是 {@code *} → 周改 {@code ?}（= 按日生效）</li>
 *     <li>只有日是 {@code *} → 日改 {@code ?}（= 按周生效）</li>
 *     <li>已经有一个 {@code ?} → 不动</li>
 *     <li><b>两个都是具体值</b>（如 {@code 0 0 9 1 * MON}）→ <b>不动，交给解析器报错</b>。
 *         这不是偷懒：这种写法本身就歧义（1 号 AND 周一？还是 OR？），
 *         想要「或」就写两条表达式 —— 宁可让它启动期炸，也不要留一个静默的歧义语义。</li>
 * </ul>
 * <br>
 * <h2>QUARTZ 方言里另外两条容易踩的</h2>
 * <ol>
 *     <li><b>周日是 1，周六是 7</b>（Quartz 的 {@code SUN=1}），与 Unix cron 的 0=周日相反。</li>
 *     <li><b>6 段（不带年）；第 7 段年是可选的。</b></li>
 * </ol>
 * 其余语法（{@code ,}、{@code -}、{@code /}、{@code L}、{@code W}、{@code #}）按 Quartz 定义走。
 *
 * <h2>解析时机</h2>
 * 构造即解析，非法表达式<b>立刻</b>抛 {@link IllegalArgumentException} ——
 * cron 写错是启动期的错误，而不是「上线三天后第一次该触发才发现写错了」。
 */
public class CronExpression {

    /** cron-utils 的解析器是线程安全的，全局共用一个即可。 */
    private static final CronParser PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private final String expression;
    private final ExecutionTime executionTime;

    /**
     * @throws IllegalArgumentException 表达式为空、段数不对、或不符合 QUARTZ 方言
     */
    public CronExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Cron 表达式不能为空");
        }
        this.expression = expression.trim();
        try {
            this.executionTime = ExecutionTime.forCron(PARSER.parse(normalize(this.expression)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Cron 表达式非法（QUARTZ 方言，6 段：秒 分 时 日 月 周；"
                            + "日与周不能同时是具体值，想表达「或」请写成两条表达式）: "
                            + this.expression, e);
        }
    }

    /**
     * 把「日/周都是 {@code *}」这类 Unix 习惯写法归一成 Quartz 能接受的形态。<br>
     * 只在<b>确实需要</b>时动手；两个都是具体值时原样返回，让它去报错。
     */
    private static String normalize(String expr) {
        String[] f = expr.split("\\s+");
        if (f.length < 6) {
            return expr; // 段数都不对，交给解析器给出它自己的报错
        }
        String dom = f[3];
        String dow = f[5];
        if ("?".equals(dom) || "?".equals(dow)) {
            return expr; // 已经符合 Quartz 规矩
        }
        if ("*".equals(dow)) {
            f[5] = "?";
            return String.join(" ", f);
        }
        if ("*".equals(dom)) {
            f[3] = "?";
            return String.join(" ", f);
        }
        // 两个都是具体值：有歧义，不猜，让它报错
        return expr;
    }

    public String getExpression() {
        return expression;
    }

    /**
     * {@code from} 之后的下一个触发时刻（严格晚于 {@code from}）。<br>
     * 返回值带 {@code from} 的时区（cron 的时刻按各时区当地时间来算）。
     */
    public ZonedDateTime next(ZonedDateTime from) {
        return executionTime.nextExecution(from)
                .orElseThrow(() -> new IllegalStateException(
                        "Cron 表达式找不到下一个执行时间: " + expression));
    }

    /** 当前时间（系统默认时区）之后的下一个触发时刻。 */
    public ZonedDateTime nextFromNow() {
        return next(ZonedDateTime.now());
    }

    /** 当前时间（指定时区）之后的下一个触发时刻。 */
    public ZonedDateTime nextFromNow(ZoneId zoneId) {
        return next(ZonedDateTime.now(zoneId));
    }

    @Override
    public String toString() {
        return "Cron[" + expression + "]";
    }
}
