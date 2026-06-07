package indi.somebottle.potatosack.utils;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Cron 表达式相关工具类
 */
public class CronUtils {
    private final ExecutionTime executionTime;

    /**
     * 初始化 CronUtils
     *
     * @param cronExpr UNIX Cron 表达式
     * @throws IllegalArgumentException 如果 Cron 表达式无效，或者找不到上一次 / 下一次执行的时间则抛出此异常
     */
    public CronUtils(String cronExpr) throws IllegalArgumentException {
        CronDefinition cronDef = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
        CronParser cronParser = new CronParser(cronDef);
        Cron cron = cronParser.parse(cronExpr);
        cron.validate();
        executionTime = ExecutionTime.forCron(cron);
        // 尝试获取上一次和下一次执行时间，确保表达式有效
        nextExecutionTimestamp(Utils.timestamp());
        timeFromLastExecution(Utils.timestamp());
    }

    /**
     * 将 UNIX 时间戳转换为 ZonedDateTime
     *
     * @param timestamp UNIX 时间戳 (秒级)
     * @return ZonedDateTime 对象
     */
    private ZonedDateTime timestampToZonedDateTime(long timestamp) {
        Instant instant = Instant.ofEpochSecond(timestamp);
        return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /**
     * 获取从指定时间戳开始的下一次执行时间戳
     *
     * @param fromTimestamp 起始时间戳（秒级）
     * @return 下一次执行时间戳（秒级）
     * @throws IllegalArgumentException 如果找不到下一次执行时间则抛出此异常
     */
    public long nextExecutionTimestamp(long fromTimestamp) throws IllegalArgumentException {
        Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(timestampToZonedDateTime(fromTimestamp));
        if (nextExecution.isEmpty()) {
            throw new IllegalArgumentException("No next execution time found for the given cron expression");
        }
        return nextExecution.get().toInstant().getEpochSecond();
    }

    /**
     * 获取从指定时间戳开始的，距离上一次执行的时间间隔（秒级，非负数）
     *
     * @param currentTimestamp 指定时间戳（UNIX 秒级）
     * @return 距离上一次执行的时间间隔（秒级，非负数）
     * @throws IllegalArgumentException 如果找不到上一次执行时间则抛出此异常
     */
    public long timeFromLastExecution(long currentTimestamp) throws IllegalArgumentException {
        Optional<Duration> duration = executionTime.timeFromLastExecution(timestampToZonedDateTime(currentTimestamp));
        if (duration.isEmpty()) {
            throw new IllegalArgumentException("No last execution time found for the given cron expression");
        }
        return duration.get().abs().getSeconds();
    }
}
