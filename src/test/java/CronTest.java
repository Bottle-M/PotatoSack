import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import indi.somebottle.potatosack.utils.CronUtils;
import indi.somebottle.potatosack.utils.Utils;
import org.junit.Test;

public class CronTest {
    @Test
    public void testCronParse() {
        String testExpression = "0 12 * * 1";
        CronDefinition cronDef = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
        CronParser parser = new CronParser(cronDef);
        Cron cron = parser.parse(testExpression);
        cron.validate();
        System.out.println("Cron expression parsed successfully: " + cron.asString());
    }

    @Test
    public void testInvalidCronParse() {
        String testExpression = "60 12 * * 1"; // Invalid minute value
        CronDefinition cronDef = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
        CronParser parser = new CronParser(cronDef);
        try {
            Cron cron = parser.parse(testExpression);
            cron.validate();
            System.out.println("Cron expression parsed successfully: " + cron.asString());
        } catch (IllegalArgumentException e) {
            System.out.println("Failed to parse cron expression: " + e.getMessage());
        }
    }

    @Test
    public void testNextExecutionTimeStamp() {
        String testExpression = "*/30 * * * *";
        CronUtils cronUtils = new CronUtils(testExpression);
        long nextExecutionTimestamp = cronUtils.nextExecutionTimestamp(Utils.timestamp());
        System.out.println("Next execution timestamp for cron expression '" + testExpression + "': " + nextExecutionTimestamp);
        System.out.println("Which is: " + Utils.timestampToDateStr(nextExecutionTimestamp));
        System.out.println("\nCurrent timestamp: " + Utils.timestamp());
        System.out.println("Which is: " + Utils.timestampToDateStr(Utils.timestamp()));
    }

    @Test
    public void testTimeFromLastExecution() {
        String testExpression = "*/30 * * * *";
        CronUtils cronUtils = new CronUtils(testExpression);
        long currTimestamp = 1765191600L;
        long timeDuration = cronUtils.timeFromLastExecution(currTimestamp);
        long lastTimestamp = currTimestamp - timeDuration;
        long lastTimeDuration = cronUtils.timeFromLastExecution(lastTimestamp);
        long nextExecutionTimestampFromLast = cronUtils.nextExecutionTimestamp(lastTimestamp);
        System.out.println(lastTimestamp + " (" + Utils.timestampToDateStr(lastTimestamp) + ") <- " + timeDuration + "s <- " + currTimestamp + " (" + Utils.timestampToDateStr(currTimestamp) + ") -> " + nextExecutionTimestampFromLast + " (" + Utils.timestampToDateStr(nextExecutionTimestampFromLast) + ")");
        System.out.println((nextExecutionTimestampFromLast - currTimestamp) + (currTimestamp - lastTimestamp));
        long nextExecutionTimestamp = cronUtils.nextExecutionTimestamp(currTimestamp);
        System.out.println("Next execution timestamp for cron expression '" + testExpression + "': " + nextExecutionTimestamp);
        System.out.println("Which is: " + Utils.timestampToDateStr(nextExecutionTimestamp));
    }
}
